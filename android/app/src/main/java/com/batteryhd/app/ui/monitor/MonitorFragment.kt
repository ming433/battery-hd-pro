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

        app.tempTracker.recordSample(snap.temperatureC, snap.isCharging)

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
                "$scene: %.1f h".format(hours)
            }
        }

        binding.btnViewReport.setOnClickListener { showWeeklyReport() }
        binding.btnUnlockReport.setOnClickListener { unlockReportWithAd() }

        refreshTemperatureChart()
        refreshWeeklyReportSummary()
    }

    private fun refreshTemperatureChart() {
        val maxTemp = app.tempTracker.getMaxTemp24h()
        val avgTemp = app.tempTracker.getAverageTemp24h()
        val samples = app.tempTracker.getChartData()

        if (samples.isEmpty()) {
            binding.tvTempChart.text = getString(R.string.temp_chart_no_data)
            binding.tvTempMax.text = ""
            binding.tvTempAvg.text = ""
        } else {
            binding.tvTempMax.text = getString(R.string.temp_chart_max, maxTemp)
            binding.tvTempAvg.text = getString(R.string.temp_chart_avg, avgTemp)

            val chartText = buildTempChartText(samples)
            binding.tvTempChart.text = chartText

            val highTempCount = app.tempTracker.getHighTempChargingCount()
            if (highTempCount > 0) {
                binding.tvTempWarning.visibility = View.VISIBLE
            } else {
                binding.tvTempWarning.visibility = View.GONE
            }
        }
    }

    private fun buildTempChartText(samples: List<Pair<Long, Float>>): String {
        if (samples.size < 2) return "Collecting data..."

        val chars = "▁▂▃▄▅▆▇█"
        val minTemp = samples.minOf { it.second }
        val maxTemp = samples.maxOf { it.second }
        val range = (maxTemp - minTemp).coerceAtLeast(1f)

        val downsampled = if (samples.size > 24) {
            val step = samples.size / 24
            samples.filterIndexed { i, _ -> i % step == 0 }.take(24)
        } else {
            samples
        }

        return downsampled.joinToString("") { (_, temp) ->
            val normalized = ((temp - minTemp) / range * 7).toInt().coerceIn(0, 7)
            chars[normalized].toString()
        }
    }

    private fun refreshWeeklyReportSummary() {
        val report = app.weeklyReportGenerator.generateReport()
        binding.tvReportSummary.text = report.summaryText

        val isUnlocked = app.weeklyReportGenerator.isFullReportUnlocked()
        if (isUnlocked) {
            binding.btnUnlockReport.visibility = View.GONE
        } else {
            binding.btnUnlockReport.visibility = View.VISIBLE
        }
    }

    private fun unlockReportWithAd() {
        app.prefs.grantCoachPass24h()

        Analytics.track(
            "ai_report_unlock_rewarded",
            mapOf("week_number" to app.prefs.currentIsoWeek())
        )

        refreshWeeklyReportSummary()
        showWeeklyReport()
    }

    private fun showWeeklyReport() {
        val report = app.weeklyReportGenerator.generateReport()
        val isUnlocked = app.weeklyReportGenerator.isFullReportUnlocked()

        Analytics.track(
            "ai_report_open",
            mapOf(
                "week_number" to report.weekNumber,
                "is_pro" to app.prefs.isPro,
                "is_unlocked" to isUnlocked
            )
        )

        if (isUnlocked) {
            binding.reportDetailsPanel.visibility = View.VISIBLE

            binding.tvReportScore.text = "${getString(R.string.weekly_report_habits_score)}: ${report.habitsScore}/100 (${report.habitsGrade})"
            binding.tvReportSessions.text = getString(R.string.weekly_report_sessions, report.totalSessions)
            binding.tvReportHighTemp.text = getString(R.string.weekly_report_high_temp, report.highTempCharges)

            if (report.actionItems.isNotEmpty()) {
                binding.tvReportActions.visibility = View.VISIBLE
                binding.tvReportActions.text = "${getString(R.string.weekly_report_actions)}:\n" +
                        report.actionItems.joinToString("\n") { "• $it" }
            } else {
                binding.tvReportActions.visibility = View.GONE
            }
        } else {
            binding.reportDetailsPanel.visibility = View.GONE
            binding.tvReportSummary.text = report.summaryText + "\n\n" +
                    getString(R.string.weekly_report_pro_only)
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
