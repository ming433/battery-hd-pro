package com.batteryhd.app.ui.home

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.R
import com.batteryhd.app.ads.AdsManager
import com.batteryhd.app.battery.BatteryRepository
import com.batteryhd.app.databinding.FragmentHomeBinding
import com.batteryhd.app.ui.MainActivity
import com.batteryhd.app.ui.reportLimitTriggered

/** 首页仪表盘 */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val app: BatteryHdApp get() = requireContext().applicationContext as BatteryHdApp

    private var scenesVisible = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentHomeBinding.bind(view)

        refresh()

        binding.cardHealth.setOnClickListener {
            val snap = app.batteryRepo.snapshot()
            Analytics.track(
                Dictionary.Event.BATTERY_HEALTH_VIEWED,
                mapOf(
                    "health_score" to snap.healthScore,
                    "capacity_mah" to snap.capacityMah,
                    "temperature_c" to snap.temperatureC
                )
            )
        }

        binding.btnExpandScenes.setOnClickListener {
            scenesVisible = !scenesVisible
            val estimates = app.batteryRepo.sceneEstimates(
                app.batteryRepo.snapshot().levelPercent
            )
            Analytics.track(
                Dictionary.Event.SCENE_ESTIMATE_EXPANDED,
                mapOf(
                    "expanded_count" to estimates.size,
                    "scenes_visible" to scenesVisible
                )
            )
            binding.tvScenes.visibility = if (scenesVisible) View.VISIBLE else View.GONE
            binding.tvScenes.text = estimates.joinToString("\n") { (scene, hours) ->
                "$scene: %.1f jam".format(hours)
            }
        }

        binding.btnPro.setOnClickListener {
            (activity as? MainActivity)?.openPro(Dictionary.Source.BANNER)
        }

        // Banner：unit_id 全部来自远程配置，此处只传容器与页面名
        app.ads.loadBanner(binding.bannerContainer, Dictionary.Screen.HOME_DASHBOARD)
    }

    private fun refresh() {
        val snap: BatteryRepository.Snapshot = app.batteryRepo.snapshot()
        binding.tvLevel.text = getString(R.string.home_level, snap.levelPercent)
        binding.progressLevel.progress = snap.levelPercent
        binding.tvStatus.text = if (snap.isCharging) {
            getString(R.string.home_charging)
        } else {
            getString(R.string.home_discharging)
        }
        binding.tvTemp.text = getString(R.string.home_temp, snap.temperatureC)
        binding.tvHealthScore.text = getString(R.string.monitor_health_score, snap.healthScore)
        binding.tvCapacity.text = getString(R.string.monitor_capacity, snap.capacityMah)

        // 达到充电限值：一次充电会话只上报一次（见 Prefs.limitNotifiedSessionStart）
        if (snap.isCharging && snap.levelPercent >= app.prefs.chargeLimitPercent) {
            reportLimitTriggered(app.prefs.chargeLimitPercent, snap)
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) refresh()
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val TAG = "HomeFragment"
    }
}
