package com.batteryhd.app.ads

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.util.Prefs
import com.google.ads.mediation.admob.AdMobAdapter
import com.google.android.gms.ads.AdListener
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdValue
import com.google.android.gms.ads.AdView
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.RequestConfiguration
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback

/**
 * 广告封装（PRD 10.5）。
 *
 * 三条硬性约束：
 * 1. **unit_id 只能来自远程配置**，绝不在代码或 XML 里硬编码。
 *    配置不可用时走 SDK 的降级链（远端 → 缓存 → 内置默认），内置默认也不能为空。
 * 2. **广告与埋点解耦**：用户关闭埋点后，广告仍要正常拉取展示；
 *    因此这里不依赖埋点开关做判断，只在成功/失败时发埋点（SDK 未初始化时是 no-op）。
 * 3. **熔断 + 频次**：连续失败进入冷却，避免用错误 unit_id 狂刷被判无效流量；
 *    频次上限由后台配置，客户端只计数。
 */
class AdsManager(
    private val app: Application,
    private val prefs: Prefs
) {

    private var interstitial: InterstitialAd? = null
    private var interstitialLoading = false

    /** 会话级插屏计数（会话结束即清零，不落盘） */
    private var interstitialInSession = 0

    /** 上次插屏展示时间，用于 min_interval */
    private var lastInterstitialAt = 0L

    // ------------------------------------------------------------ 初始化

    fun initialize() {
        MobileAds.initialize(app) {
            applyRequestConfiguration()
        }
        preloadInterstitial()
    }

    /**
     * 应用远程配置中的合规项。
     * `tag_for_child_directed` 与 `max_ad_content_rating` 设错会被 AdMob 直接停号，
     * 所以只能来自后台（且后台侧要求双人复核），客户端不得自行发明默认值。
     */
    private fun applyRequestConfiguration() {
        val cfg = Analytics.ads.current()
        val builder = RequestConfiguration.Builder()
            .setTagForChildDirectedTreatment(
                if (cfg.global.tagForChildDirected) {
                    RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_TRUE
                } else {
                    RequestConfiguration.TAG_FOR_CHILD_DIRECTED_TREATMENT_FALSE
                }
            )
            .setMaxAdContentRating(cfg.global.maxAdContentRating)
        runCatching { MobileAds.setRequestConfiguration(builder.build()) }
    }

    /** npa（非个性化广告）：用户未同意个性化时降级 */
    private fun buildRequest(): AdRequest {
        val builder = AdRequest.Builder()
        if (Analytics.ads.current().global.npa) {
            val extras = Bundle().apply { putString("npa", "1") }
            builder.addNetworkExtrasBundle(AdMobAdapter::class.java, extras)
        }
        return builder.build()
    }

    // ------------------------------------------------------------ Banner

    /**
     * 加载 Banner 并注入容器。
     * 任何不可展示的情况（位关闭 / 熔断中 / unit_id 为空）都不留下空白占位，
     * 直接隐藏容器，避免布局抖动。
     */
    fun loadBanner(container: FrameLayout, screenName: String) {
        val key = Placements.BANNER_HOME
        val unitId = Analytics.ads.resolveUnitId(key)

        if (unitId.isNullOrBlank()) {
            container.visibility = View.GONE
            Analytics.track(
                Dictionary.Event.AD_CONFIG_INVALID,
                mapOf("placement_id" to key, "reason" to "empty_unit_id")
            )
            return
        }
        if (!canRequest(key, scene = Dictionary.TriggerScene.APP_COLD_START)) {
            container.visibility = View.GONE
            return
        }

        Analytics.track(
            Dictionary.Event.AD_REQUESTED,
            mapOf(
                "placement_id" to key,
                "ad_unit_id" to unitId,
                "network" to Dictionary.Network.ADMOB,
                "ad_format" to Dictionary.AdFormat.BANNER,
                "trigger_scene" to Dictionary.TriggerScene.APP_COLD_START,
                // 一次"展示意图"只计一次；AdMob 内部重试不重复上报本事件
                "retry_index" to 0
            )
        )

        val adView = AdView(container.context)
        adView.adUnitId = unitId
        adView.setAdSize(AdSize.BANNER)

        adView.onPaidEventListener = com.google.android.gms.ads.OnPaidEventListener { adValue ->
            reportRevenue(key, unitId, adValue)
        }

        adView.adListener = object : AdListener() {
            override fun onAdLoaded() {
                Analytics.ads.recordSuccess(key)
                container.removeAllViews()
                container.addView(adView)
                container.visibility = View.VISIBLE
                Analytics.track(
                    Dictionary.Event.BANNER_SHOWN,
                    mapOf(
                        "placement_id" to key,
                        "ad_unit_id" to unitId,
                        "ad_format" to Dictionary.AdFormat.BANNER,
                        "screen_name" to screenName
                    )
                )
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                Analytics.ads.recordFailure(key, error.code)
                container.visibility = View.GONE
                Analytics.track(
                    Dictionary.Event.AD_LOAD_FAILED,
                    mapOf(
                        "placement_id" to key,
                        "ad_unit_id" to unitId,
                        "error_code" to error.code,
                        "network_type" to networkType()
                    )
                )
                notifyCircuitBreakerIfTripped(key)
            }

            override fun onAdClicked() {
                Analytics.track(
                    Dictionary.Event.BANNER_CLICKED,
                    mapOf(
                        "placement_id" to key,
                        "ad_unit_id" to unitId,
                        "screen_name" to screenName
                    )
                )
            }
        }

        adView.loadAd(buildRequest())
    }

    // ------------------------------------------------------------ 插屏

    fun preloadInterstitial() {
        val key = Placements.INTERSTITIAL_AFTER_CALIBRATION
        val unitId = Analytics.ads.resolveUnitId(key)
        if (unitId.isNullOrBlank() || interstitial != null || interstitialLoading) return
        if (!canRequest(key, scene = Dictionary.TriggerScene.CHARGE_COMPLETE)) return

        interstitialLoading = true
        Analytics.track(
            Dictionary.Event.AD_REQUESTED,
            mapOf(
                "placement_id" to key,
                "ad_unit_id" to unitId,
                "network" to Dictionary.Network.ADMOB,
                "ad_format" to Dictionary.AdFormat.INTERSTITIAL,
                "trigger_scene" to Dictionary.TriggerScene.CHARGE_COMPLETE,
                "retry_index" to 0
            )
        )

        InterstitialAd.load(app, unitId, buildRequest(), object : InterstitialAdLoadCallback() {
            override fun onAdLoaded(ad: InterstitialAd) {
                interstitialLoading = false
                interstitial = ad
                Analytics.ads.recordSuccess(key)
                ad.onPaidEventListener = com.google.android.gms.ads.OnPaidEventListener { adValue ->
                    reportRevenue(key, unitId, adValue)
                }
            }

            override fun onAdFailedToLoad(error: LoadAdError) {
                interstitialLoading = false
                interstitial = null
                Analytics.ads.recordFailure(key, error.code)
                Analytics.track(
                    Dictionary.Event.AD_LOAD_FAILED,
                    mapOf(
                        "placement_id" to key,
                        "ad_unit_id" to unitId,
                        "error_code" to error.code,
                        "network_type" to networkType()
                    )
                )
                notifyCircuitBreakerIfTripped(key)
            }
        })
    }

    /**
     * 展示插屏。
     *
     * @return true 表示已展示（调用方应等关闭回调再继续）；
     *         false 表示不展示（调用方直接继续，不打断用户流程）。
     *
     * 注意：**任何情况下都不能因为广告而阻塞业务流程**——
     * 未预加载成功、被熔断、频次超限都走 false 分支。
     */
    fun showInterstitial(activity: Activity, onClosed: () -> Unit): Boolean {
        val key = Placements.INTERSTITIAL_AFTER_CALIBRATION
        val ad = interstitial ?: return false

        if (!canShow(key)) {
            interstitial = null
            return false
        }

        val unitId = Analytics.ads.resolveUnitId(key) ?: return false
        val shownAt = System.currentTimeMillis()

        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdDismissedFullScreenContent() {
                interstitial = null
                Analytics.track(
                    Dictionary.Event.INTERSTITIAL_CLOSED,
                    mapOf(
                        "placement_id" to key,
                        "ad_unit_id" to unitId,
                        "close_time_ms" to (System.currentTimeMillis() - shownAt),
                        "was_skipped" to false
                    )
                )
                preloadInterstitial()
                onClosed()
            }

            override fun onAdFailedToShowFullScreenContent(error: com.google.android.gms.ads.AdError) {
                interstitial = null
                Analytics.ads.recordFailure(key, error.code)
                Analytics.track(
                    Dictionary.Event.AD_LOAD_FAILED,
                    mapOf(
                        "placement_id" to key,
                        "ad_unit_id" to unitId,
                        "error_code" to error.code,
                        "network_type" to networkType()
                    )
                )
                onClosed()
            }

            override fun onAdShowedFullScreenContent() {
                interstitialInSession++
                lastInterstitialAt = System.currentTimeMillis()
                prefs.incrementInterstitialToday()
                Analytics.track(
                    Dictionary.Event.INTERSTITIAL_SHOWN,
                    mapOf(
                        "placement_id" to key,
                        "ad_unit_id" to unitId,
                        "trigger_scene" to Dictionary.TriggerScene.CHARGE_COMPLETE,
                        "session_event_count" to interstitialInSession
                    )
                )
            }
        }

        ad.show(activity)
        return true
    }

    // ------------------------------------------------------------ 判断

    /** 是否允许发起请求（含熔断与新用户宽限） */
    private fun canRequest(key: String, scene: String): Boolean {
        val cfg = Analytics.ads.current()
        if (!cfg.isEnabled(key)) return false
        if (isInGracePeriod(cfg.global.firstLaunchGraceMinutes)) return false

        if (Analytics.ads.isCoolingDown(key)) {
            Analytics.track(
                Dictionary.Event.AD_CIRCUIT_BREAKER_TRIPPED,
                mapOf("placement_id" to key, "fail_count" to 0)
            )
            return false
        }

        val placement = cfg.placements[key] ?: return false
        if (placement.triggerScenes.isNotEmpty() && scene !in placement.triggerScenes) return false
        return true
    }

    /** 是否允许展示（在 canRequest 基础上叠加频次限制与 Pro 免广告） */
    private fun canShow(key: String): Boolean {
        if (prefs.isPro) return false   // Pro 用户免广告

        val cfg = Analytics.ads.current()
        if (!cfg.isEnabled(key)) return false
        if (isInGracePeriod(cfg.global.firstLaunchGraceMinutes)) return false

        val cap = cfg.placements[key]?.frequencyCap ?: return true
        if (interstitialInSession >= cap.perSession) return false
        if (prefs.interstitialCountToday() >= cap.perDay) return false
        val elapsedSec = (System.currentTimeMillis() - lastInterstitialAt) / 1000
        if (lastInterstitialAt > 0 && elapsedSec < cap.minIntervalSeconds) return false

        return true
    }

    /** 新用户首次启动后的免广告宽限期 */
    private fun isInGracePeriod(minutes: Int): Boolean {
        if (minutes <= 0) return false
        val first = prefs.firstLaunchAt
        if (first <= 0L) return false
        return System.currentTimeMillis() - first < minutes * 60_000L
    }

    // ------------------------------------------------------------ 收入埋点

    /**
     * AdMob 收入回调 —— **eCPM 的唯一数据来源**（PRD 10.6）。
     *
     * revenue_micros 是百万分之一货币单位，服务端计算 eCPM 时会 ÷ 10^6，
     * 客户端不要自行换算，避免二次出错。
     */
    private fun reportRevenue(
        placementId: String,
        unitId: String,
        adValue: AdValue
    ) {
        Analytics.track(
            Dictionary.Event.AD_REVENUE_PAID,
            mapOf(
                "placement_id" to placementId,
                "ad_unit_id" to unitId,
                "network" to Dictionary.Network.ADMOB,
                // eCPM 唯一数据源：AdMob 以 micros（百万分之一货币单位）返回，服务端按
                // (Σ micros ÷ 1e6) ÷ 展示量 × 1000 计算，客户端不做换算
                "revenue_micros" to adValue.valueMicros,
                "currency" to adValue.currencyCode,
                "precision_type" to precisionName(adValue.precisionType)
            )
        )
    }

    /**
     * 当前网络类型，用于 `ad_load_failed.network_type`。
     *
     * 只做只读查询且整体 try/catch：网络状态不是核心指标，
     * **任何异常都不能反过来导致广告回调崩溃**（回调里抛异常会打断广告流程）。
     */
    private fun networkType(): String {
        return try {
            val cm = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return Dictionary.NetworkType.UNKNOWN
            val net = cm.activeNetwork ?: return Dictionary.NetworkType.OFFLINE
            val caps = cm.getNetworkCapabilities(net) ?: return Dictionary.NetworkType.UNKNOWN
            when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Dictionary.NetworkType.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Dictionary.NetworkType.CELLULAR
                else -> Dictionary.NetworkType.UNKNOWN
            }
        } catch (t: Throwable) {
            Dictionary.NetworkType.UNKNOWN
        }
    }

    private fun precisionName(precision: Int): String = when (precision) {
        PRECISION_ESTIMATED -> Dictionary.PrecisionType.ESTIMATED
        PRECISION_PUBLISHER_PROVIDED -> Dictionary.PrecisionType.PUBLISHER_PROVIDED
        PRECISION_PRECISE -> Dictionary.PrecisionType.PRECISE
        PRECISION_UNKNOWN -> Dictionary.PrecisionType.UNKNOWN
        else -> Dictionary.PrecisionType.UNKNOWN
    }

    private fun notifyCircuitBreakerIfTripped(key: String) {
        if (Analytics.ads.isCoolingDown(key)) {
            Analytics.track(
                Dictionary.Event.AD_CIRCUIT_BREAKER_TRIPPED,
                mapOf("placement_id" to key)
            )
        }
    }

    /** 会话重置：App 进后台再回来视为新会话 */
    fun onNewSession() {
        interstitialInSession = 0
    }

    object Placements {
        const val BANNER_HOME = "banner_home"
        const val INTERSTITIAL_AFTER_CALIBRATION = "interstitial_after_calibration"
    }

    /**
     * AdMob `precisionType` 取值。
     *
     * play-services-ads 23.x 已移除 `AdValue.PRECISION_TYPE_*` 常量
     * （反编译该类只剩 getPrecisionType / getValueMicros / getCurrencyCode），
     * 因此按官方文档固定取值并集中在此，便于日后核对。
     */
    private companion object {
        const val PRECISION_UNKNOWN = 0
        const val PRECISION_ESTIMATED = 1
        const val PRECISION_PUBLISHER_PROVIDED = 2
        const val PRECISION_PRECISE = 3
    }
}
