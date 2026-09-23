package com.batteryhd.analytics

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import org.json.JSONObject
import java.util.UUID

/**
 * 埋点 SDK 门面（PRD 10.3）。
 *
 * ── 初始化时机（重要）─────────────────────────────────────
 * 必须在**用户已做出隐私选择之后**才调用 [init]。
 * 若先初始化再读同意状态，启动瞬间的数据已被采集，等同于未获同意即收集，
 * 在 PDP Law / PDPA 下属于实质性违规。推荐用法：
 *
 * ```
 * if (!ConsentManager.hasDecided(context)) {
 *     showPrivacyDialog()          // 用户选择后再 init
 * } else {
 *     Analytics.init(app, config)
 * }
 * ```
 *
 * ── 性能约束（PRD 10.3.5）────────────────────────────────
 * [track] 在主线程只做「校验 + 内存入队」，落盘与网络均在 IO 协程，
 * 实测应 ≤ 5ms，绝不阻塞主线程。
 */
object Analytics {

    private var initialized = false

    private lateinit var app: Application
    private lateinit var config: AnalyticsConfig
    private lateinit var scope: CoroutineScope

    private lateinit var deviceInfo: DeviceInfo
    private lateinit var session: SessionManager
    private lateinit var store: EventStore
    private lateinit var uploader: EventUploader
    private lateinit var prefs: SharedPreferences

    @Volatile
    private var remoteConfig = AnalyticsRemoteConfig()

    @Volatile
    private var authToken: String? = null

    @Volatile
    private var country: String = ""

    @Volatile
    private var isPro: Boolean = false

    // ── 页面停留时长（screen_exited.duration_ms 的唯一数据来源）────────────
    // 「功能使用深度/停留时长」看板完全依赖本事件；若只在宿主页手动上报，
    // 任何一处漏调都会让该页时长永久缺失，因此改为 SDK 自动配对结算。
    @Volatile
    private var currentScreen: String? = null

    @Volatile
    private var screenEnterAtMs: Long = 0L

    /** 进入后台的时间戳，用于 app_foregrounded.background_duration_ms */
    @Volatile
    private var backgroundedAtMs: Long = 0L

    /** 广告配置门面（供业务层读取广告位 unit_id 与频次策略） */
    val ads: AdConfigManager get() = adConfigManager
    private lateinit var adConfigManager: AdConfigManager

    private lateinit var api: ApiClient

    fun init(application: Application, cfg: AnalyticsConfig) {
        if (initialized) return

        app = application
        config = cfg
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        AnalyticsLogger.enabled = cfg.logging

        deviceInfo = DeviceInfo(application)
        session = SessionManager(application)
        store = EventStore(application, AnalyticsRemoteConfig.DEFAULT_MAX_OFFLINE)
        prefs = application.getSharedPreferences(PREF_BHD, Context.MODE_PRIVATE)

        authToken = prefs.getString(KEY_TOKEN, null)
        country = prefs.getString(KEY_COUNTRY, "") ?: ""
        isPro = prefs.getBoolean(KEY_IS_PRO, false)

        api = ApiClient(cfg.apiBaseUrl)

        uploader = EventUploader(
            scope = scope,
            api = api,
            store = store,
            session = session,
            deviceInfo = deviceInfo,
            tokenProvider = { authToken },
            configProvider = { remoteConfig }
        )

        adConfigManager = AdConfigManager(
            context = application,
            scope = scope,
            api = api,
            config = cfg,
            tokenProvider = { authToken },
            isProProvider = { isPro },
            countryProvider = { country }
        )

        initialized = true

        observeLifecycle()
        uploader.startPeriodicUpload()

        // 首次注册（无 token 时）；已有 token 则异步刷新配置
        if (authToken == null) {
            register()
        } else {
            scope.launch(Dispatchers.IO) { refreshConfigs() }
        }
    }

    // ------------------------------------------------------------ 生命周期

    private fun observeLifecycle() {
        scope.launch(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {

                override fun onStart(owner: LifecycleOwner) {
                    val now = System.currentTimeMillis()

                    if (session.isTimedOut()) {
                        // 会话已超时（或首次）→ 冷启动，开新会话
                        val newId = session.startNewSession()
                        session.incrementSessionIndex()
                        track(
                            Dictionary.Event.APP_LAUNCHED,
                            // is_first_launch 不在此事件：首次启动由 app_first_open 单独表达
                            mapOf("launch_type" to Dictionary.LaunchType.COLD)
                        )
                        AnalyticsLogger.d("New session: $newId")
                    } else if (backgroundedAtMs > 0L) {
                        // 会话未超时，只是短暂切走再回来（PRD 10.2.4 A）
                        // 与 app_launched 的区别：不重开会话，只记后台时长。
                        // 若二者混用，会话数与 DAU 会被每次切后台放大数倍。
                        track(
                            Dictionary.Event.APP_FOREGROUNDED,
                            mapOf("background_duration_ms" to (now - backgroundedAtMs))
                        )
                    }
                    backgroundedAtMs = 0L

                    scope.launch(Dispatchers.IO) {
                        if (adConfigManager.shouldRefresh()) adConfigManager.refresh()
                    }
                }

                override fun onStop(owner: LifecycleOwner) {
                    backgroundedAtMs = System.currentTimeMillis()

                    // 退后台时结算当前页停留时长（否则该页时长永久丢失）
                    endCurrentScreen()

                    // 后台：强制 flush，保证离线数据不丢
                    track(
                        Dictionary.Event.APP_BACKGROUNDED,
                        mapOf(
                            "session_duration_ms" to (System.currentTimeMillis() - session.startedAt),
                            "screens_visited" to session.sessionDepth
                        )
                    )
                    uploader.flush()
                }
            })
        }
    }

    // -------------------------------------------------------------- 上报

    /**
     * 记录一条事件。主线程安全，耗时 ≤ 5ms（仅校验 + 入内存队列）。
     *
     * @param name 事件名，必须是 [Dictionary.Event] 中的值
     * @param properties 事件属性；不在白名单内的键会被服务端剥离，
     *                   枚举值非法的整条事件会被服务端拒收。
     */
    @JvmOverloads
    fun track(name: String, properties: Map<String, Any?> = emptyMap()) {
        if (!initialized) return
        if (!isEnabled()) return
        if (remoteConfig.disabledEvents.contains(name)) return

        // 采样：按 device_id 稳定分桶（PRD P1——随机采样会让漏斗与留存断裂）
        if (!shouldSample(name)) return

        val enriched = HashMap<String, Any?>(properties.size + 10).apply {
            putAll(properties)
            put("session_id", session.sessionId)
            put("session_index", session.sessionIndex)
            put("country", country)
            put("network_type", deviceInfo.networkType)
            put("device_model", deviceInfo.deviceModel)
            put("manufacturer", deviceInfo.manufacturer)
            put("is_pro", isPro)
            put("sample_rate", remoteConfig.samplingRate)
        }

        val record = EventRecord(
            eventName = name,
            properties = enriched,
            clientTimestamp = uploader.correctedTimestamp(),
            sessionId = session.sessionId
        )

        // IO 落盘，不阻塞主线程
        scope.launch(Dispatchers.IO) {
            store.insert(record)

            val cfg = remoteConfig
            if (store.count() >= cfg.batchSize) uploader.flush()
        }

        session.touch()
    }

    /**
     * 页面浏览：自动维护 previous_screen、session_depth，
     * 并**自动结算上一页的 screen_exited**。
     *
     * 自动配对而非要求宿主页成对调用：漏调一处该页时长就永久缺失，
     * 而「平均停留时长」是功能使用看板的核心指标。
     */
    fun trackScreen(screenName: String, previousScreen: String? = null) {
        endCurrentScreen()

        currentScreen = screenName
        screenEnterAtMs = System.currentTimeMillis()

        val depth = session.nextDepth()
        track(
            Dictionary.Event.SCREEN_VIEWED,
            mapOf(
                "screen_name" to screenName,
                "previous_screen" to previousScreen,   // 首屏为 null，服务端豁免校验
                "session_depth" to depth
            )
        )
    }

    /**
     * 页面退出：结算停留时长（PRD 10.2.4 B）。
     *
     * 宿主页可在 onPause 显式调用以获得更精确的时长；不调用也不会丢——
     * 下一次 [trackScreen] 或退后台时 SDK 会自动补发。
     */
    fun trackScreenExit() {
        endCurrentScreen()
    }

    /** 结算并上报当前页停留时长；无当前页或时长非法时静默跳过 */
    private fun endCurrentScreen() {
        val name = currentScreen ?: return
        val enteredAt = screenEnterAtMs
        currentScreen = null
        screenEnterAtMs = 0L
        if (enteredAt <= 0L) return

        val durationMs = System.currentTimeMillis() - enteredAt
        // 时长为负/为 0 说明时钟被回拨或同一毫秒内进出，上报没有意义
        if (durationMs <= 0L) return

        track(
            Dictionary.Event.SCREEN_EXITED,
            mapOf(
                "screen_name" to name,
                "duration_ms" to durationMs
            )
        )
    }

    // -------------------------------------------------------- 采样与开关

    private fun isEnabled(): Boolean = remoteConfig.analyticsEnabled

    /**
     * 稳定分桶采样：同一设备对同一事件的结果恒定。
     * 若用随机数，同一用户的漏斗事件会时有时无，漏斗转化率必然失真。
     */
    private fun shouldSample(name: String): Boolean {
        val rate = remoteConfig.samplingRate
        if (rate >= 1.0) return true
        if (name in Dictionary.NO_SAMPLING_EVENTS) return true

        val bucket = (crc32(session.deviceId + name) % 10000).absoluteValue
        return bucket < (rate * 10000).toInt()
    }

    /**
     * CRC32，与服务端 PHP 的 crc32() 同算法，保证两端灰度分桶一致。
     * 多项式 0xEDB88320 是 CRC-32/ISO-HDLC 的反射形式（PHP crc32 用的就是它）。
     */
    private fun crc32(input: String): Int {
        var crc = -1   // 0xFFFFFFFF
        val poly = 0xEDB88320.toInt()
        for (b in input.toByteArray()) {
            crc = crc xor (b.toInt() and 0xFF)
            repeat(8) {
                crc = if ((crc and 1) != 0) (crc ushr 1) xor poly else crc ushr 1
            }
        }
        return crc.inv()
    }

    // -------------------------------------------------------------- 注册

    private fun register() {
        scope.launch(Dispatchers.IO) {
            val payload = JSONObject().apply {
                put("device_uuid", session.deviceId)
                put("app_version", deviceInfo.appVersion)
                put("os_version", deviceInfo.osVersion)
                put("locale", deviceInfo.locale)
                put("device_model", deviceInfo.deviceModel)
                put("manufacturer", deviceInfo.manufacturer)
                // Play Integrity token 由宿主 App 注入（PRD 10.4.2 防伪造注册）
                // 未注入时服务端按 IP 限流兜底
            }

            // api.register 已返回 Result，不要再套一层 runCatching（会变成 Result<Result<...>>）
            api.register(payload)
                .onSuccess { res ->
                    authToken = res.token
                    uploader.updateClockOffset(res.serverTimestamp)
                    prefs.edit().putString(KEY_TOKEN, res.token).apply()

                    track(
                        Dictionary.Event.APP_FIRST_OPEN,
                        mapOf("is_first_launch" to true)
                    )
                    refreshConfigs()
                }
                .onFailure { e ->
                    AnalyticsLogger.e("Register failed", e)
                }
        }
    }

    private suspend fun refreshConfigs() {
        val token = authToken ?: return

        // 埋点配置
        when (val res = api.fetchAnalyticsConfig(token)) {
            is ApiClient.Result.Success -> runCatching {
                val json = JSONObject(res.body)
                val data = json.optJSONObject("data") ?: json
                remoteConfig = AnalyticsRemoteConfig(
                    analyticsEnabled = data.optBoolean("analytics_enabled", true),
                    batchSize = data.optInt("batch_size", AnalyticsRemoteConfig.DEFAULT_BATCH_SIZE),
                    uploadIntervalSeconds = data.optInt("upload_interval_seconds", AnalyticsRemoteConfig.DEFAULT_UPLOAD_INTERVAL),
                    maxRetryCount = data.optInt("max_retry_count", AnalyticsRemoteConfig.DEFAULT_MAX_RETRY),
                    maxOfflineEvents = data.optInt("max_offline_events", AnalyticsRemoteConfig.DEFAULT_MAX_OFFLINE),
                    samplingRate = data.optDouble("sampling_rate", 1.0),
                    disabledEvents = data.optJSONArray("disabled_events")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }.toSet()
                    } ?: emptySet()
                )
                store.maxEvents = remoteConfig.maxOfflineEvents
                // 服务端判定的国家为准（PRD：country 必须两端同源）
                val serverCountry = data.optString("country", "")
                if (serverCountry.isNotBlank()) {
                    country = serverCountry
                    prefs.edit().putString(KEY_COUNTRY, serverCountry).apply()
                }
            }.onFailure { AnalyticsLogger.e("Parse analytics config failed", it) }

            is ApiClient.Result.Failure -> AnalyticsLogger.w("Fetch analytics config failed: ${res.code}")
        }

        // 广告配置（独立端点，不受埋点开关影响）
        adConfigManager.refresh()
    }

    // -------------------------------------------------------- 同意凭证

    /**
     * 同步同意凭证到服务端（PRD 10.7.3）。
     *
     * 走独立端点而**不是**事件通道：撤回同意后事件通道会被关闭，
     * 若把"撤回"塞进事件队列，这条记录将永远发不出去，
     * 服务端会一直以为用户仍处于已同意状态——这在合规审计中是硬伤。
     *
     * @param action granted（首次同意）/ withdrawn（撤回）/ updated（变更）
     */
    fun syncConsent(
        action: String,
        analyticsEnabled: Boolean,
        personalizedAds: Boolean,
        policyVersion: String
    ) {
        val token = authToken ?: return

        scope.launch(Dispatchers.IO) {
            runCatching {
                api.reportConsent(
                    authToken = token,
                    action = action,
                    analytics = analyticsEnabled,
                    personalizedAds = personalizedAds,
                    policyVersion = policyVersion,
                    decidedAtMs = System.currentTimeMillis()
                )
            }.onFailure { AnalyticsLogger.e("Report consent failed", it) }
        }
    }

    /** 同意动作取值（与服务端 consent_records.action 枚举一致） */
    object ConsentAction {
        const val GRANTED = "granted"
        const val WITHDRAWN = "withdrawn"
        const val UPDATED = "updated"
    }

    // ---------------------------------------------------------- 外部注入

    /** 订阅状态由订阅模块注入（PRD 10.0 外部依赖） */
    fun setProStatus(pro: Boolean) {
        isPro = pro
        if (initialized) prefs.edit().putBoolean(KEY_IS_PRO, pro).apply()
    }

    /** 国家由服务端回写；仅在服务端未返回时由宿主注入兜底值 */
    fun setCountry(code: String) {
        country = code
        if (initialized) prefs.edit().putString(KEY_COUNTRY, code).apply()
    }

    /** 手动触发上报（如 FCM 静默唤醒时） */
    fun flush() {
        if (initialized) uploader.flush()
    }

    /**
     * 用户行使删除权（PRD 10.7.3）：停止采集并清除本地全部数据。
     * 注意：服务端删除需另行调用删除接口，本方法只处理端上数据。
     */
    fun deleteLocalData() {
        if (!initialized) return
        store.clearAll()
        adConfigManager.clearCache()
        prefs.edit().clear().apply()
        authToken = null
        remoteConfig = remoteConfig.copy(analyticsEnabled = false)
    }

    /** 设置页开关：用户关闭后 SDK 完全停止采集与上报（PRD 10.8 验收项） */
    fun setAnalyticsEnabled(enabled: Boolean) {
        remoteConfig = remoteConfig.copy(analyticsEnabled = enabled)
        if (!enabled) {
            store.clearAll()
            AnalyticsLogger.d("Analytics disabled by user, local queue cleared")
        }
    }

    fun isInitialized(): Boolean = initialized

    /** 仅测试用：注入配置 */
    internal fun injectConfigForTest(cfg: AnalyticsRemoteConfig) {
        remoteConfig = cfg
    }

    private const val PREF_BHD = "bhd_analytics"
    private const val KEY_TOKEN = "auth_token"
    private const val KEY_COUNTRY = "country"
    private const val KEY_IS_PRO = "is_pro"
}

/** 内部日志（release 环境默认关闭） */
internal object AnalyticsLogger {
    @Volatile
    var enabled: Boolean = false

    fun d(msg: String) { if (enabled) android.util.Log.d(TAG, msg) }
    fun w(msg: String) { if (enabled) android.util.Log.w(TAG, msg) }
    fun e(msg: String, t: Throwable? = null) { if (enabled) android.util.Log.e(TAG, msg, t) }

    private const val TAG = "BatteryHD-Analytics"
}
