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
        binding.toolbar.setNavigationOnClickListener { finish() }

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

        if (!app.billing.isConfigured) {
            binding.tvPrice.setText(R.string.pro_not_configured)
            binding.btnBuy.isEnabled = false
        } else {
            app.billing.queryProducts { price ->
                runOnUiThread {
                    binding.tvPrice.text = when {
                        price.isNotEmpty() -> price
                        app.billing.isPlayReady -> getString(R.string.pro_not_configured)
                        else -> getString(R.string.pro_billing_unavailable)
                    }
                }
            }
        }

        binding.btnBuy.setOnClickListener {
            if (!app.billing.isConfigured) {
                Toast.makeText(this, R.string.pro_not_configured, Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            app.billing.purchase(this) { ok ->
                if (!ok && !isFinishing) {
                    Toast.makeText(this, R.string.pro_billing_unavailable, Toast.LENGTH_LONG).show()
                }
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
        val PLAN_ID: String = BillingManager.PLAN_ID

        fun intent(context: Context, source: String): Intent =
            Intent(context, ProActivity::class.java).putExtra(EXTRA_SOURCE, source)
    }
}
