package com.batteryhd.analytics

import android.content.Context
import android.content.SharedPreferences

/**
 * 用户同意状态管理（PRD 10.3.3 / 10.7.2）。
 *
 * **关键约束**：同意状态必须在 SDK **初始化之前**读取并生效。
 * 若先初始化再读状态，启动瞬间（Application.onCreate 到用户操作之间）的事件已经被采集，
 * 这在 PDP Law / PDPA 下等同于"未获同意即收集"，属于实质性违规。
 *
 * 因此本类不依赖 SDK 其余部分，可独立构造使用：
 * ```
 * if (!ConsentManager.hasDecided(context)) { 展示隐私政策，暂不初始化 SDK }
 * ```
 */
class ConsentManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    /** 用户是否已做出选择（未选择 = 未同意，SDK 不采集） */
    fun hasDecided(): Boolean = prefs.contains(KEY_ANALYTICS)

    /** 是否同意埋点采集 */
    fun isAnalyticsEnabled(): Boolean =
        prefs.getBoolean(KEY_ANALYTICS, false)

    /** 是否同意个性化广告（npa=非个性化。不同意则降级为非个性化广告） */
    fun isPersonalizedAdsAllowed(): Boolean =
        prefs.getBoolean(KEY_PERSONALIZED_ADS, false)

    /** 当前同意对应的隐私政策版本号 */
    fun policyVersion(): String = prefs.getString(KEY_POLICY_VERSION, DEFAULT_POLICY_VERSION)
        ?: DEFAULT_POLICY_VERSION

    /** 用户做出选择的时间戳（毫秒） */
    fun decidedAt(): Long = prefs.getLong(KEY_DECIDED_AT, 0L)

    /**
     * 记录用户选择。
     *
     * @param analytics 是否同意埋点
     * @param personalizedAds 是否同意个性化广告
     * @param policyVersion 当时展示的隐私政策版本号。
     *   合规举证需要它：不同版本下的同意不能混为一谈，
     *   且政策更新后需要重新征得同意。
     */
    fun setConsent(
        analytics: Boolean,
        personalizedAds: Boolean,
        policyVersion: String = DEFAULT_POLICY_VERSION
    ) {
        prefs.edit()
            .putBoolean(KEY_ANALYTICS, analytics)
            .putBoolean(KEY_PERSONALIZED_ADS, personalizedAds)
            .putString(KEY_POLICY_VERSION, policyVersion)
            .putLong(KEY_DECIDED_AT, System.currentTimeMillis())
            .apply()
    }

    /** 用户行使删除权（PRD 10.7.3）：清空本地全部埋点数据与同意凭证 */
    fun resetForDataDeletion() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREF_NAME = "bhd_consent"
        private const val KEY_ANALYTICS = "analytics_enabled"
        private const val KEY_PERSONALIZED_ADS = "personalized_ads"
        private const val KEY_POLICY_VERSION = "policy_version"
        private const val KEY_DECIDED_AT = "decided_at"

        /**
         * 隐私政策版本号（兜底）。
         *
         * 宿主应在政策更新时传入新版本号——版本号变化意味着需要重新征得同意，
         * 否则拿旧版本下取得的同意去支撑新版本的数据收集，举证时站不住。
         */
        const val DEFAULT_POLICY_VERSION = "2026-09-01"

        /**
         * 静态方法：无需构造 SDK 即可判断用户是否已表态。
         * 用于 App 启动入口决定是否展示隐私政策弹窗。
         */
        fun hasDecided(context: Context): Boolean =
            context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).contains(KEY_ANALYTICS)
    }
}
