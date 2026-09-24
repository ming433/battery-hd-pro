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
import com.batteryhd.app.coach.CoachInsight
import com.batteryhd.app.databinding.FragmentHomeBinding
import com.batteryhd.app.ui.MainActivity
import com.batteryhd.app.ui.reportLimitTriggered

/** Home dashboard with Battery Guru-inspired card layout */
class HomeFragment : Fragment(R.layout.fragment_home) {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private val app: BatteryHdApp get() = requireContext().applicationContext as BatteryHdApp

    private var scenesVisible = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentHomeBinding.bind(view)

        refresh()
        refreshInsight()

        binding.btnRefreshInsight.setOnClickListener {
            refreshInsight()
            Analytics.track(
                "ai_insight_refresh",
                mapOf("source" to "manual")
            )
        }

        binding.btnInsightAction.setOnClickListener {
            val insight = app.coachRepo.getCachedInsight() ?: return@setOnClickListener
            handleInsightAction(insight.primaryAction)
            Analytics.track(
                "ai_insight_action_click",
                mapOf("action" to insight.primaryAction.name)
            )
        }

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
                "$scene: %.1f h".format(hours)
            }
        }

        binding.btnPro.setOnClickListener {
            (activity as? MainActivity)?.openPro(Dictionary.Source.BANNER)
        }

        // Ask Coach (M3)
        setupAskCoach()

        // Banner：unit_id 全部来自远程配置，此处只传容器与页面名
        app.ads.loadBanner(binding.bannerContainer, Dictionary.Screen.HOME_DASHBOARD)
    }

    // ------------------------------------------------------------ Ask Coach (M3)

    private fun setupAskCoach() {
        binding.btnAskCoach.setOnClickListener {
            val question = binding.etAskCoach.text?.toString()?.trim() ?: ""
            if (question.isNotEmpty()) {
                askCoachQuestion(question)
            }
        }

        binding.btnCoachAction.setOnClickListener {
            val response = lastCoachResponse ?: return@setOnClickListener
            handleInsightAction(response.suggestedAction)
        }
    }

    private var lastCoachResponse: com.batteryhd.app.coach.AskCoach.CoachResponse? = null

    private fun askCoachQuestion(question: String) {
        val response = app.askCoach.ask(question)
        lastCoachResponse = response

        binding.tvCoachResponse.visibility = View.VISIBLE
        binding.tvCoachResponse.text = response.answer

        if (response.wasBlocked) {
            Analytics.track(
                "ai_ask_coach_blocked",
                mapOf("block_reason" to (response.blockReason ?: "unknown"))
            )
        } else {
            Analytics.track(
                "ai_ask_coach_query",
                mapOf(
                    "confidence" to response.confidence,
                    "has_action" to (response.suggestedAction != CoachInsight.PrimaryAction.NONE)
                )
            )
        }

        if (response.suggestedAction != CoachInsight.PrimaryAction.NONE && !response.wasBlocked) {
            binding.btnCoachAction.visibility = View.VISIBLE
            binding.btnCoachAction.text = when (response.suggestedAction) {
                CoachInsight.PrimaryAction.SET_CHARGE_LIMIT -> getString(R.string.ai_insight_action_set_limit)
                CoachInsight.PrimaryAction.START_CALIBRATION -> getString(R.string.ai_insight_action_calibrate)
                CoachInsight.PrimaryAction.OPEN_DRAIN_DETAIL -> getString(R.string.ai_insight_action_drain_detail)
                CoachInsight.PrimaryAction.ENABLE_TEMP_ALERT -> getString(R.string.ai_insight_action_temp_alert)
                else -> ""
            }
        } else {
            binding.btnCoachAction.visibility = View.GONE
        }

        binding.etAskCoach.text?.clear()
    }

    private fun refresh() {
        val snap: BatteryRepository.Snapshot = app.batteryRepo.snapshot()

        binding.tvLevel.text = snap.levelPercent.toString()
        binding.progressLevel.progress = snap.levelPercent

        binding.chipStatus.text = if (snap.isCharging) {
            getString(R.string.home_charging)
        } else {
            getString(R.string.home_discharging)
        }

        binding.tvTemp.text = getString(R.string.home_temp, snap.temperatureC)

        binding.tvHealthScore.text = getString(R.string.monitor_health_percent, snap.healthScore)

        val designCapacity = 4855
        val currentCapacity = (designCapacity * snap.healthScore / 100)
        binding.tvCapacity.text = "$currentCapacity of $designCapacity mAh"

        val remainingHours = (snap.levelPercent * 10 / 100)
        val remainingMinutes = (snap.levelPercent * 10 % 100) * 60 / 100
        binding.tvRemainingTime.text = getString(R.string.home_remaining_time, remainingHours, remainingMinutes)

        val usedPercent = 100 - snap.levelPercent
        binding.tvSessionUsed.text = "-${usedPercent}%"
        binding.tvSessionMah.text = "${usedPercent * 50} mAh"

        if (snap.isCharging && snap.levelPercent >= app.prefs.chargeLimitPercent) {
            reportLimitTriggered(app.prefs.chargeLimitPercent, snap)
        }
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) {
            refresh()
            showCachedInsight()
        }
    }

    private fun refreshInsight() {
        val insight = app.coachRepo.generateInsight()
        displayInsight(insight)
        Analytics.track(
            "ai_insight_show",
            mapOf(
                "confidence" to insight.confidence.name,
                "action" to insight.primaryAction.name
            )
        )
    }

    private fun showCachedInsight() {
        val cached = app.coachRepo.getCachedInsight()
        if (cached != null) {
            displayInsight(cached.copy(isOffline = true))
        }
    }

    private fun displayInsight(insight: CoachInsight) {
        if (_binding == null) return

        binding.tvInsightHeadline.text = insight.headline
        binding.tvInsightSummary.text = insight.summary
        binding.tvInsightOffline.visibility = if (insight.isOffline) View.VISIBLE else View.GONE

        if (insight.primaryAction != CoachInsight.PrimaryAction.NONE) {
            binding.btnInsightAction.visibility = View.VISIBLE
            binding.btnInsightAction.text = when (insight.primaryAction) {
                CoachInsight.PrimaryAction.SET_CHARGE_LIMIT -> getString(R.string.ai_insight_action_set_limit)
                CoachInsight.PrimaryAction.START_CALIBRATION -> getString(R.string.ai_insight_action_calibrate)
                CoachInsight.PrimaryAction.OPEN_DRAIN_DETAIL -> getString(R.string.ai_insight_action_drain_detail)
                CoachInsight.PrimaryAction.ENABLE_TEMP_ALERT -> getString(R.string.ai_insight_action_temp_alert)
                CoachInsight.PrimaryAction.NONE -> ""
            }
        } else {
            binding.btnInsightAction.visibility = View.GONE
        }
    }

    private fun handleInsightAction(action: CoachInsight.PrimaryAction) {
        val mainActivity = activity as? MainActivity ?: return
        when (action) {
            CoachInsight.PrimaryAction.SET_CHARGE_LIMIT -> mainActivity.navigateToCharge()
            CoachInsight.PrimaryAction.START_CALIBRATION -> mainActivity.navigateToCharge()
            CoachInsight.PrimaryAction.OPEN_DRAIN_DETAIL -> mainActivity.navigateToPower()
            CoachInsight.PrimaryAction.ENABLE_TEMP_ALERT -> mainActivity.navigateToCharge()
            CoachInsight.PrimaryAction.NONE -> {}
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    private companion object {
        const val TAG = "HomeFragment"
    }
}
