package com.batteryhd.app.ui.monitor

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.R
import com.batteryhd.app.databinding.FragmentMonitorBinding

/** 电池监测详情 */
class MonitorFragment : Fragment(R.layout.fragment_monitor) {

    private var _binding: FragmentMonitorBinding? = null
    private val binding get() = _binding!!

    private val app: BatteryHdApp get() = requireContext().applicationContext as BatteryHdApp

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentMonitorBinding.bind(view)

        val snap = app.batteryRepo.snapshot()
        binding.tvHealthScore.text = getString(R.string.monitor_health_score, snap.healthScore)
        binding.tvCapacity.text = getString(R.string.monitor_capacity, snap.capacityMah)
        binding.tvVoltage.text = getString(R.string.monitor_voltage, snap.voltageV)
        binding.tvTechnology.text = getString(R.string.monitor_technology, snap.technology)

        // 进入本页即视为"查看健康度详情"
        Analytics.track(
            Dictionary.Event.BATTERY_HEALTH_VIEWED,
            mapOf(
                "health_score" to snap.healthScore,
                "capacity_mah" to snap.capacityMah,
                "temperature_c" to snap.temperatureC
            )
        )

        binding.btnExpand.setOnClickListener {
            val estimates = app.batteryRepo.sceneEstimates(snap.levelPercent)
            Analytics.track(
                Dictionary.Event.SCENE_ESTIMATE_EXPANDED,
                mapOf(
                    "expanded_count" to estimates.size,
                    "scenes_visible" to true
                )
            )
            binding.tvScenes.visibility = View.VISIBLE
            binding.tvScenes.text = estimates.joinToString("\n") { (scene, hours) ->
                "$scene: %.1f jam".format(hours)
            }
        }

        binding.btnWeeklyReport.setOnClickListener { showWeeklyReport() }
    }

    /**
     * 周报（PRD 10.2.4 D：weekly_report_viewed）。
     *
     * 数据来自端上的**周聚合**而非逐条明细（见 Prefs.recordWeeklySession）：
     * 端上保留明细既无必要，也会放大本地存储与合规风险。
     */
    private fun showWeeklyReport() {
        val week = app.prefs.currentIsoWeek()
        val sessions = app.prefs.weeklySessionCount
        val avgMs = app.prefs.weeklyAverageDurationMs()

        Analytics.track(
            Dictionary.Event.WEEKLY_REPORT_VIEWED,
            mapOf(
                "week_number" to week,
                "report_type" to Dictionary.ReportType.WEEKLY
            )
        )

        binding.tvWeeklyReport.visibility = View.VISIBLE
        binding.tvWeeklyReport.text = if (sessions > 0) {
            getString(R.string.report_weekly_title, week) + "\n" +
                getString(R.string.report_weekly_summary, formatDuration(avgMs), sessions)
        } else {
            getString(R.string.report_no_data)
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalMinutes = (ms / 60_000L).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}j ${minutes}m" else "${minutes}m"
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
