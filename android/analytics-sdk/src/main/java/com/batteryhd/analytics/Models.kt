package com.batteryhd.analytics

/**
 * 数据模型与配置（PRD 10.3）。
 */

/** 单条事件 */
internal data class EventRecord(
    val id: Long = 0,
    val eventName: String,
    val properties: Map<String, Any?>,
    val clientTimestamp: Long,
    val sessionId: String,
    val retryCount: Int = 0,
    val createdAt: Long = System.currentTimeMillis()
)

/** 上报批次 */
internal data class Batch(
    val batchId: String,
    val sessionId: String,
    val events: List<EventRecord>
)

/**
 * SDK 配置。
 *
 * [defaults] 为 L0 内置默认值（PRD 10.5.2）：打包进客户端的兜底配置，
 * 保证无网 / 服务端故障时依然有可用的广告配置，**绝不允许为空**——
 * 空 unit_id 会导致 AdMob 请求无效流量，严重时会牵连整个 AdMob 账号。
 */
data class AnalyticsConfig(
    val apiBaseUrl: String,
    val appVersion: String,
    /** AdMob App ID，内置兜底 */
    val admobAppId: String,
    /** 广告位兜底配置：placementKey -> unitId。必须非空 */
    val defaultAdUnits: Map<String, String>,
    val debug: Boolean = false,
    /** 是否启用日志（release 应关闭） */
    val logging: Boolean = false
)

/** 埋点远程配置（服务端下发，PRD 10.3.6） */
internal data class AnalyticsRemoteConfig(
    val analyticsEnabled: Boolean = true,
    val batchSize: Int = DEFAULT_BATCH_SIZE,
    val uploadIntervalSeconds: Int = DEFAULT_UPLOAD_INTERVAL,
    val maxRetryCount: Int = DEFAULT_MAX_RETRY,
    val maxOfflineEvents: Int = DEFAULT_MAX_OFFLINE,
    val samplingRate: Double = 1.0,
    val disabledEvents: Set<String> = emptySet()
) {
    companion object {
        const val DEFAULT_BATCH_SIZE = 20
        const val DEFAULT_UPLOAD_INTERVAL = 30
        const val DEFAULT_MAX_RETRY = 5
        const val DEFAULT_MAX_OFFLINE = 500
    }
}

/**
 * 广告配置（PRD 10.5）。
 *
 * 由服务端按 L1~L5 优先级链解析后下发，客户端只需按 [placements] 使用，
 * 不自行做优先级合并——避免两端逻辑不一致导致"后台改了但客户端算错"。
 */
data class AdConfig(
    val configVersion: Int,
    val ttlSeconds: Int = 300,
    val global: AdGlobalConfig,
    val placements: Map<String, AdPlacementConfig>
) {
    /** 该广告位是否可请求广告（总开关 + 熔断 + 广告位自身开关） */
    fun isEnabled(placementKey: String): Boolean =
        global.adsEnabled && !global.killSwitch && (placements[placementKey]?.enabled == true)

    fun unitId(placementKey: String): String? =
        placements[placementKey]?.adUnitId?.takeIf { it.isNotBlank() }
}

data class AdGlobalConfig(
    val adsEnabled: Boolean = true,
    val killSwitch: Boolean = false,
    val firstLaunchGraceMinutes: Int = 10,
    val tagForChildDirected: Boolean = false,
    val maxAdContentRating: String = "T",
    val npa: Boolean = false
)

data class AdPlacementConfig(
    val placementId: String,
    val enabled: Boolean = true,
    val network: String = "admob",
    val adUnitId: String = "",
    val adFormat: String = "banner",
    /** 频次上限 */
    val frequencyCap: FrequencyCap? = null,
    /** 触发场景白名单；为空表示不限制 */
    val triggerScenes: List<String> = emptyList(),
    val loadTimeoutMs: Int = 5000,
    val retryCount: Int = 2,
    val circuitBreaker: CircuitBreakerConfig? = null,
    /** Banner 刷新间隔 */
    val refreshIntervalSeconds: Int? = null
)

data class FrequencyCap(
    val perSession: Int = Int.MAX_VALUE,
    val perDay: Int = Int.MAX_VALUE,
    val minIntervalSeconds: Int = 0
)

data class CircuitBreakerConfig(
    val failThreshold: Int = 3,
    val cooldownSeconds: Int = 900
)
