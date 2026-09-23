package com.batteryhd.app.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.R
import com.batteryhd.app.billing.BillingManager
import com.batteryhd.app.databinding.ActivityProBinding

/**
 * Pro 升级页。
 *
 * 埋点只覆盖行为漏斗：浏览 → 发起 → 完成/取消/失败。
 * 收入真相源是服务端 RTDN（退款、续订、离线购买客户端都看不到），
 * 两者通过 order_id 对齐（PRD 10.4.4）。
 */
class ProActivity : AppCompatActivity() {

    private lateinit var binding: ActivityProBinding
    private val app: BatteryHdApp get() = application as BatteryHdApp

    private val source: String by lazy {
        intent.getStringExtra(EXTRA_SOURCE) ?: Dictionary.Source.SETTINGS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityProBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Analytics.track(
            Dictionary.Event.PRO_PAGE_VIEWED,
            mapOf(
                "source" to source,
                "current_plan" to if (app.prefs.isPro) {
                    Dictionary.CurrentPlan.PRO
                } else {
                    Dictionary.CurrentPlan.FREE
                }
            )
        )

        if (app.prefs.isPro) {
            binding.btnBuy.isEnabled = false
            binding.btnBuy.text = getString(R.string.pro_owned)
        }

        app.billing.queryProducts { price ->
            binding.tvPrice.text = price
        }

        binding.btnBuy.setOnClickListener {
            val ok = app.billing.launchPurchase(this)
            if (!ok) {
                Toast.makeText(this, R.string.pro_price_error, Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnRestore.setOnClickListener {
            app.billing.restore()
            Toast.makeText(this, R.string.pro_restore, Toast.LENGTH_SHORT).show()
        }

        app.onProChanged = { pro ->
            if (pro) {
                binding.btnBuy.isEnabled = false
                binding.btnBuy.text = getString(R.string.pro_owned)
            }
        }
    }

    override fun onDestroy() {
        app.onProChanged = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SOURCE = "source"
        const val PLAN_ID: String = BillingManager.PLAN_ID

        fun intent(context: Context, source: String): Intent =
            Intent(context, ProActivity::class.java).putExtra(EXTRA_SOURCE, source)
    }
}
