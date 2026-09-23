package com.batteryhd.analytics

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * 广告配置门面 + 三级降级 + 熔断（PRD 10.5.6）。
 *
 * **降级链**（逐级兜底，任何一环失败都不能出现"空 unit_id 狂刷"）：
 * ```
 * L0 内置默认（AnalyticsConfig.defaultAdUnits，打包进客户端，必须有值）
 *  ↓ 远端不可用时
 * L1 本地缓存（上次成功拉取的结果，持久化到 SharedPreferences）
 *  ↓ 缓存也没有
 * L0 内置默认
 *  ↓ 连内置都没有 → 关闭该广告位（绝不请求空 ID）
 * ```
 *
 * **熔断**：同一广告位连续失败达到阈值即进入冷却期，冷却期内不再请求。
 * 这既保护用户体验（不反复等待加载失败），也避免用过期/错误的 unit_id
 * 持续请求被判定为无效流量——严重时会牵连整个 AdMob 账号。
 *
 * **与埋点解耦**：本类不依赖埋点开关。用户关闭埋点后广告配置仍可拉取、广告正常展示。
 */
class AdConfigManager internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val api: ApiClient,
    private val config: AnalyticsConfig,
    private val tokenProvider: () -> String?,
    private val isProProvider: () -> Boolean,
    private val countryProvider: () -> String
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var current: AdConfig = builtinFallback()

    /** 每个广告位的连续失败次数与冷却截止时间 */
    private val failCounts = ConcurrentHashMap<String, Int>()
    private val cooldownUntil = ConcurrentHashMap<String, Long>()

    private var lastFetchAt = 0L

    /** 当前生效配置（供业务层读取） */
    fun current(): AdConfig = current

    /** TTL 到期或从未拉取过时需要刷新（PRD：TTL 300s + 前后台切换 + FCM 唤醒） */
    fun shouldRefresh(): Boolean =
        System.currentTimeMillis() - lastFetchAt > current.ttlSeconds * 1000L

    /** 异步拉取；失败时静默回退，不影响调用方 */
    fun refresh(onDone: ((Boolean) -> Unit)? = null) {
        scope.launch(Dispatchers.IO) {
            val ok = try {
                fetchInternal()
            } catch (e: Exception) {
                AnalyticsLogger.e("AdConfig refresh failed", e)
                false
            }
            onDone?.invoke(ok)
        }
    }

    private fun fetchInternal(): Boolean {
        val token = tokenProvider() ?: return false
        val startedAt = System.currentTimeMillis()

        val result = api.fetchAdConfig(
            authToken = token,
            country = countryProvider(),
            appVersion = config.appVersion,
            isPro = isProProvider()
        )

        return when (result) {
            is ApiClient.Result.Success -> {
                val parsed = runCatching { parse(result.body) }.getOrNull()
                if (parsed == null) {
                    AnalyticsLogger.w("AdConfig parse failed, keep previous")
                    false
                } else {
                    current = parsed
                    lastFetchAt = System.currentTimeMillis()
                    persist(result.body)
                    AnalyticsLogger.d("AdConfig v${parsed.configVersion} applied")
                    true
                }
            }

            is ApiClient.Result.Failure -> {
                // 拉取失败 → 回退到本地缓存 → 再回退到内置默认
                AnalyticsLogger.w("AdConfig fetch failed: ${result.code}")
                restoreFromCache()
                false
            }
        }.also { success ->
            // 配置拉取本身也是一条埋点（T3 技术事件），用于监控下发链路健康度
            Analytics.track(
                Dictionary.Event.AD_CONFIG_FETCHED,
                mapOf(
                    "config_version" to current.configVersion,
                    "source" to if (success) "remote" else "fallback",
                    "duration_ms" to (System.currentTimeMillis() - startedAt),
                    "success" to success
                )
            )
        }
    }

    // ---------------------------------------------------------- 熔断逻辑

    /** 广告加载失败：累加失败计数，达到阈值则进入冷却 */
    fun recordFailure(placementKey: String, errorCode: Int?) {
        val cfg = current.placements[placementKey]?.circuitBreaker
            ?: CircuitBreakerConfig()

        val count = failCounts.merge(placementKey, 1, Int::plus) ?: 1

        if (count >= cfg.failThreshold) {
            cooldownUntil[placementKey] = System.currentTimeMillis() + cfg.cooldownSeconds * 1000L
            failCounts[placementKey] = 0
            Analytics.track(
                Dictionary.Event.AD_CIRCUIT_BREAKER_TRIPPED,
                mapOf("placement_id" to placementKey, "fail_count" to count,
                    "cooldown_seconds" to cfg.cooldownSeconds)
            )
        }

        Analytics.track(
            Dictionary.Event.AD_LOAD_FAILED,
            mapOf(
                "placement_id" to placementKey,
                "ad_unit_id" to (current.placements[placementKey]?.adUnitId ?: ""),
                "error_code" to errorCode,
                "network_type" to "unknown"
            )
        )
    }

    fun recordSuccess(placementKey: String) {
        failCounts[placementKey] = 0
        cooldownUntil.remove(placementKey)
    }

    /** 是否处于冷却期（冷却中不应发起请求） */
    fun isCoolingDown(placementKey: String): Boolean {
        val until = cooldownUntil[placementKey] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(placementKey)
            return false
        }
        return true
    }

    /**
     * 业务层唯一入口：取该广告位当前可用的 unit_id。
     * 返回 null 表示"不应请求广告"（总开关关闭 / 熔断 / 冷却 / 无有效 ID）。
     */
    fun resolveUnitId(placementKey: String): String? {
        if (!current.isEnabled(placementKey)) return null
        if (isCoolingDown(placementKey)) return null

        val unit = current.unitId(placementKey)
        if (unit.isNullOrBlank()) {
            // 远端无有效 ID → 退回内置默认（L0），内置也没有则关闭该位
            val builtin = config.defaultAdUnits[placementKey]
            if (builtin.isNullOrBlank()) {
                Analytics.track(
                    Dictionary.Event.AD_CONFIG_INVALID,
                    mapOf("placement_id" to placementKey, "reason" to "empty_unit_id")
                )
                return null
            }
            return builtin
        }
        return unit
    }

    // ------------------------------------------------------------ 解析

    private fun parse(body: String): AdConfig {
        val json = JSONObject(body)
        val data = json.optJSONObject("data") ?: json

        val globalJson = data.optJSONObject("global") ?: JSONObject()
        val global = AdGlobalConfig(
            adsEnabled = globalJson.optBoolean("ads_enabled", true),
            killSwitch = globalJson.optBoolean("kill_switch", false),
            firstLaunchGraceMinutes = globalJson.optInt("first_launch_grace_minutes", 10),
            tagForChildDirected = globalJson.optBoolean("tag_for_child_directed", false),
            maxAdContentRating = globalJson.optString("max_ad_content_rating", "T"),
            npa = globalJson.optBoolean("npa", false)
        )

        val placementsJson = data.optJSONObject("placements") ?: JSONObject()
        val placements = mutableMapOf<String, AdPlacementConfig>()
        placementsJson.keys().forEach { key ->
            val p = placementsJson.getJSONObject(key)
            val capJson = p.optJSONObject("frequency_cap")
            val cbJson = p.optJSONObject("circuit_breaker")
            val scenesJson = p.optJSONArray("trigger_scenes")

            placements[key] = AdPlacementConfig(
                placementId = p.optString("placement_id", key),
                enabled = p.optBoolean("enabled", true),
                network = p.optString("network", "admob"),
                adUnitId = p.optString("ad_unit_id", ""),
                adFormat = p.optString("ad_format", "banner"),
                frequencyCap = capJson?.let {
                    FrequencyCap(
                        perSession = it.optInt("per_session", Int.MAX_VALUE),
                        perDay = it.optInt("per_day", Int.MAX_VALUE),
                        minIntervalSeconds = it.optInt("min_interval_seconds", 0)
                    )
                },
                triggerScenes = scenesJson?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList(),
                loadTimeoutMs = p.optInt("load_timeout_ms", 5000),
                retryCount = p.optInt("retry_count", 2),
                circuitBreaker = cbJson?.let {
                    CircuitBreakerConfig(
                        failThreshold = it.optInt("fail_threshold", 3),
                        cooldownSeconds = it.optInt("cooldown_seconds", 900)
                    )
                },
                refreshIntervalSeconds = if (p.has("refresh_interval_seconds")) {
                    p.optInt("refresh_interval_seconds", 60)
                } else null
            )
        }

        return AdConfig(
            configVersion = data.optInt("config_version", 0),
            ttlSeconds = data.optInt("ttl_seconds", 300),
            global = global,
            placements = placements
        )
    }

    /**
     * L0 内置兜底配置：全部使用 AnalyticsConfig.defaultAdUnits 的值。
     * 只在冷启动且无任何缓存时使用，**保证 unit_id 不为空**。
     */
    private fun builtinFallback(): AdConfig = AdConfig(
        configVersion = 0,
        ttlSeconds = 300,
        global = AdGlobalConfig(),
        placements = config.defaultAdUnits.mapValues { (key, unitId) ->
            AdPlacementConfig(placementId = key, adUnitId = unitId, enabled = unitId.isNotBlank())
        }
    )

    // ------------------------------------------------------------ 缓存

    private fun persist(body: String) {
        prefs.edit()
            .putString(KEY_CACHED_JSON, body)
            .putLong(KEY_CACHED_AT, System.currentTimeMillis())
            .apply()
    }

    private fun restoreFromCache(): Boolean {
        val cached = prefs.getString(KEY_CACHED_JSON, null) ?: return false
        val parsed = runCatching { parse(cached) }.getOrNull() ?: return false
        current = parsed
        AnalyticsLogger.d("AdConfig restored from cache v${parsed.configVersion}")
        return true
    }

    /** 用户行使删除权时清掉本地缓存配置 */
    fun clearCache() {
        prefs.edit().remove(KEY_CACHED_JSON).remove(KEY_CACHED_AT).apply()
        current = builtinFallback()
    }

    companion object {
        private const val PREF_NAME = "bhd_ad_config"
        private const val KEY_CACHED_JSON = "cached_json"
        private const val KEY_CACHED_AT = "cached_at"
    }
}
