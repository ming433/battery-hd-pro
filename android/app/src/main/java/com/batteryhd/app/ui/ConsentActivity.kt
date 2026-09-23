package com.batteryhd.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.batteryhd.analytics.ConsentManager
import com.batteryhd.analytics.Dictionary
import com.batteryhd.analytics.Analytics
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.databinding.ActivityConsentBinding

/**
 * 隐私同意页（launcher）。
 *
 * 关键：**这是第一个界面，SDK 在此之前绝不初始化**。
 * 用户做出选择后才启动埋点与广告；选择结果由 [ConsentManager] 持久化，
 * 下次启动由 Application 直接读取，不再打扰用户。
 */
class ConsentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityConsentBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 已表态则不再打扰：直接进主界面。
        // 此时 SDK 已由 BatteryHdApp.onCreate 初始化（它同样判断 hasDecided），
        // 这里必须 return，否则既重复弹同意页、又会让 binding 未初始化就被用到。
        if (ConsentManager.hasDecided(this)) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        binding = ActivityConsentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnAccept.setOnClickListener { decide(analytics = true, personalizedAds = true) }
        binding.btnDecline.setOnClickListener { decide(analytics = false, personalizedAds = false) }

        binding.tvPrivacy.setOnClickListener {
            // 打开隐私政策：页面埋点为 help_webview（PRD Screen 枚举）
            Analytics.trackScreen(Dictionary.Screen.HELP_WEBVIEW, Dictionary.Screen.HELP_WEBVIEW)
            runCatching {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PRIVACY_URL)))
            }
        }
    }

    private fun decide(analytics: Boolean, personalizedAds: Boolean) {
        ConsentManager(this).setConsent(
            analytics = analytics,
            personalizedAds = personalizedAds,
            policyVersion = ConsentManager.DEFAULT_POLICY_VERSION
        )

        val app = application as BatteryHdApp
        app.startAnalytics()

        // 同意凭证必须在 SDK 初始化之后同步（需要 token）。
        // 注意：即使用户选择"拒绝"，也要上报——拒绝同样是需要留存的凭证。
        // 少了它，服务端只剩"同意"没有"拒绝"，等于默认所有人都是同意的。
        Analytics.syncConsent(
            action = if (analytics) Analytics.ConsentAction.GRANTED else Analytics.ConsentAction.WITHDRAWN,
            analyticsEnabled = analytics,
            personalizedAds = personalizedAds,
            policyVersion = ConsentManager.DEFAULT_POLICY_VERSION
        )

        Analytics.track(
            Dictionary.Event.ONBOARDING_STEP_COMPLETED,
            // step_name 必须是服务端枚举内的值，否则整条事件会被拒收：
            // 隐私同意是引导第一步，对应 welcome
            mapOf(
                "step_index" to 0,
                "step_name" to Dictionary.StepName.WELCOME
            )
        )

        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

    companion object {
        private const val PRIVACY_URL = "https://batteryhd.pro/privacy"
    }
}
