package com.batteryhd.app

import android.app.Application
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.AnalyticsConfig
import com.batteryhd.analytics.ConsentManager
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.ads.AdsManager
import com.batteryhd.app.battery.BatteryRepository
import com.batteryhd.app.battery.ChargeSessionTracker
import com.batteryhd.app.billing.BillingManager
import com.batteryhd.app.coach.CoachInsightRepository
import com.batteryhd.app.coach.LocalCoachInsightRepository
import com.batteryhd.app.util.Prefs

/**
 * Application 入口。
 *
 * ── 初始化时序（合规关键）─────────────────────────────────────
 * 埋点 SDK **必须**在用户做出隐私选择之后才初始化。
 * 因此初始化不由 Application 单方面决定：
 *   - 用户已表态   → [startAnalytics] 在 onCreate 直接调用
 *   - 用户未表态   → 交给 [com.batteryhd.app.ui.ConsentActivity] 决定后再调用
 * 若在这里无条件 init，启动瞬间的数据已被采集，等同未获同意即收集，
 * 在 PDP Law / PDPA 下是实质性违规。
 */
class BatteryHdApp : Application() {

    lateinit var prefs: Prefs
        private set
    lateinit var batteryRepo: BatteryRepository
        private set
    lateinit var chargeTracker: ChargeSessionTracker
        private set
    lateinit var ads: AdsManager
        private set
    lateinit var billing: BillingManager
        private set
    lateinit var coachRepo: CoachInsightRepository
        private set

    /** Pro 状态变化回调（UI 订阅用于隐藏广告入口） */
    var onProChanged: ((Boolean) -> Unit)? = null

    /**
     * App 是否在前台（`notification_received.is_foreground`）。
     *
     * 用 Activity 计数而不是单一标志：存在多个 Activity 时（如设置页压栈）
     * 简单的 started/stopped 布尔量会被后启动的 Activity 错误覆盖。
     */
    @Volatile
    var isForeground: Boolean = false
        private set

    override fun onCreate() {
        super.onCreate()

        observeForeground()

        prefs = Prefs(this)
        batteryRepo = BatteryRepository(this)
        chargeTracker = ChargeSessionTracker(this, prefs, batteryRepo)
        ads = AdsManager(this, prefs)
        billing = BillingManager(this, prefs) { pro -> onProChanged?.invoke(pro) }
        coachRepo = LocalCoachInsightRepository(batteryRepo, prefs)
        billing.start()

        if (prefs.firstLaunchAt == 0L) {
            prefs.firstLaunchAt = System.currentTimeMillis()
        }

        // 充电会话对账必须在注册广播之前，否则刚补报完立刻又收到广播会重复
        chargeTracker.reconcile()
        chargeTracker.register()

        if (ConsentManager.hasDecided(this)) {
            startAnalytics()
        }
    }

    private var foregroundActivities = 0

    private fun observeForeground() {
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {

            override fun onActivityStarted(activity: android.app.Activity) {
                foregroundActivities++
                isForeground = true
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                foregroundActivities = (foregroundActivities - 1).coerceAtLeast(0)
                isForeground = foregroundActivities > 0
            }

            override fun onActivityCreated(a: android.app.Activity, b: android.os.Bundle?) {}
            override fun onActivityResumed(a: android.app.Activity) {}
            override fun onActivityPaused(a: android.app.Activity) {}
            override fun onActivitySaveInstanceState(a: android.app.Activity, b: android.os.Bundle) {}
            override fun onActivityDestroyed(a: android.app.Activity) {}
        })
    }

    /**
     * 启动埋点与广告配置。
     *
     * 注意与埋点开关的关系：即使用户在设置里关闭了埋点，这里**仍然初始化 SDK**，
     * 只是随后调用 [Analytics.setAnalyticsEnabled] 停止采集。
     * 原因是广告配置下发走同一个 SDK 通道，若不初始化，用户关闭埋点后
     * 广告也将拿不到 unit_id —— 那等于让埋点开关牵连变现链路，
     * 违反 PRD 10.1 原则 5「埋点与广告系统隔离」。
     */
    fun startAnalytics() {
        val consent = ConsentManager(this)

        Analytics.init(
            application = this,
            cfg = AnalyticsConfig(
                apiBaseUrl = BuildConfig.API_BASE_URL,
                appVersion = BuildConfig.VERSION_NAME,
                admobAppId = ADMOB_APP_ID_FALLBACK,
                defaultAdUnits = DEFAULT_AD_UNITS,
                debug = BuildConfig.DEBUG,
                logging = BuildConfig.DEBUG
            )
        )

        if (!consent.isAnalyticsEnabled()) {
            Analytics.setAnalyticsEnabled(false)
        }
        Analytics.setProStatus(prefs.isPro)

        ads.initialize()
        billing.start()

        Analytics.track(
            Dictionary.Event.APP_LAUNCHED,
            mapOf(
                "launch_type" to Dictionary.LaunchType.COLD,
                // 上限 60s：低端机冷启动可能很久，避免异常值污染启动耗时分布
                "launch_duration_ms" to (System.currentTimeMillis() - prefs.firstLaunchAt).coerceAtMost(60_000L)
            )
        )
    }

    companion object {
        /**
         * AdMob App ID（L0 内置兜底）。
         * 与 AndroidManifest 中的测试 ID 保持一致，仅用于开发期；
         * 正式包必须按 flavor 注入真实 App ID。
         */
        const val ADMOB_APP_ID_FALLBACK = "ca-app-pub-3940256099942544~3347511713"

        /**
         * 广告位兜底配置（L0）。
         *
         * **绝不允许为空**——空 unit_id 会让 AdMob 认为在请求无效流量，
         * 严重时会牵连整个 AdMob 账号。这里填官方测试 ID，
         * 上线前必须与后台 ad_units 表的真实 ID 保持一致。
         */
        val DEFAULT_AD_UNITS: Map<String, String> = mapOf(
            AdsManager.Placements.BANNER_HOME to "ca-app-pub-3940256099942544/6300978111",
            AdsManager.Placements.INTERSTITIAL_AFTER_CALIBRATION to "ca-app-pub-3940256099942544/1033173712"
        )
    }
}
