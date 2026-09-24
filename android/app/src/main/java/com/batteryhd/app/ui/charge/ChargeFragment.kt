package com.batteryhd.app.ui.charge

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.R
import com.batteryhd.app.coach.ChargePlanAdvisor
import com.batteryhd.app.coach.SmartLimitAdvisor
import com.batteryhd.app.databinding.FragmentChargeBinding
import com.batteryhd.app.ui.reportLimitTriggered
import com.batteryhd.app.ui.reportTempAlert
import com.google.android.material.snackbar.Snackbar

/**
 * 充电保护：限值提醒、温度告警、电池校准。
 *
 * 校准是三步流程（放电 → 充满 → 校验），真实耗时数小时，
 * **中途退出是常态**。若只上报 started/completed，校准流失率就完全不可见，
 * 因此放弃路径与完成路径同样重要。
 */
class ChargeFragment : Fragment(R.layout.fragment_charge) {

    private var _binding: FragmentChargeBinding? = null
    private val binding get() = _binding!!

    private val app: BatteryHdApp get() = requireContext().applicationContext as BatteryHdApp

    /** 温度告警阈值（°C）。超过 45°C 会显著加速电池老化 */
    private val tempThresholdC = 45f

    private lateinit var smartLimitAdvisor: SmartLimitAdvisor

    private var planReadyByHour = 7
    private var planReadyByMinute = 0

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentChargeBinding.bind(view)

        smartLimitAdvisor = SmartLimitAdvisor(app.batteryRepo, app.prefs)

        val limit = app.prefs.chargeLimitPercent
        binding.sliderLimit.value = limit.toFloat()
        binding.tvLimit.text = getString(R.string.charge_limit_label, limit)

        binding.sliderLimit.addOnChangeListener { _, value, fromUser ->
            val percent = value.toInt()
            app.prefs.chargeLimitPercent = percent
            binding.tvLimit.text = getString(R.string.charge_limit_label, percent)
            if (fromUser) {
                checkLimitAndAlert(percent)
                updateSmartLimitDifferentWarning()
            }
        }

        binding.btnCalibrate.setOnClickListener { startCalibration() }
        binding.btnCalibrationNext.setOnClickListener { advanceCalibration(view) }
        binding.btnCalibrationCancel.setOnClickListener {
            abandonCalibration(Dictionary.CalibrationAbandonReason.USER_CANCEL)
            Snackbar.make(view, R.string.charge_calibration_abandoned, Snackbar.LENGTH_SHORT).show()
        }

        binding.btnApplySuggestion.setOnClickListener {
            applySuggestedLimit()
        }

        // 进程被杀/重建后恢复进行中的校准
        restoreCalibration()

        // Tonight's Charge Plan (M2)
        setupChargePlan()

        refresh()
        refreshSmartLimitSuggestion()
        refreshChargePlan()
    }

    // ------------------------------------------------------------ Charge Plan (M2)

    private fun setupChargePlan() {
        binding.btnSetTime.setOnClickListener {
            TimePickerDialog(
                requireContext(),
                { _, hour, minute ->
                    planReadyByHour = hour
                    planReadyByMinute = minute
                    binding.btnSetTime.text = String.format("%02d:%02d", hour, minute)
                },
                planReadyByHour,
                planReadyByMinute,
                true
            ).show()
        }

        binding.btnCreatePlan.setOnClickListener {
            createChargePlan()
        }

        binding.btnClearPlan.setOnClickListener {
            app.chargePlanAdvisor.clearPlan()
            refreshChargePlan()
            Analytics.track("ai_charge_plan_clear", emptyMap())
        }

        binding.sliderPlanTarget.addOnChangeListener { _, _, _ -> }
    }

    private fun createChargePlan() {
        val targetPercent = binding.sliderPlanTarget.value.toInt()
        val input = ChargePlanAdvisor.PlanInput(
            readyByHour = planReadyByHour,
            readyByMinute = planReadyByMinute,
            targetPercent = targetPercent
        )

        val plan = app.chargePlanAdvisor.generatePlan(input)
        app.chargePlanAdvisor.savePlan(plan)

        refreshChargePlan()

        Analytics.track(
            "ai_charge_plan_create",
            mapOf(
                "target_percent" to plan.targetPercent,
                "ready_by_hour" to plan.readyByHour,
                "has_heat_guidance" to (plan.heatPauseGuidance != null)
            )
        )

        Snackbar.make(requireView(), R.string.charge_plan_active, Snackbar.LENGTH_SHORT).show()
    }

    private fun refreshChargePlan() {
        val savedPlan = app.chargePlanAdvisor.getSavedPlan()

        if (savedPlan != null) {
            binding.chargePlanSetup.visibility = View.GONE
            binding.chargePlanActive.visibility = View.VISIBLE

            binding.tvPlanSummary.text = savedPlan.summary

            if (savedPlan.heatPauseGuidance != null) {
                binding.tvPlanHeatWarning.visibility = View.VISIBLE
                binding.tvPlanHeatWarning.text = savedPlan.heatPauseGuidance
            } else {
                binding.tvPlanHeatWarning.visibility = View.GONE
            }
        } else {
            binding.chargePlanSetup.visibility = View.VISIBLE
            binding.chargePlanActive.visibility = View.GONE
        }
    }

    private fun refresh() {
        val snap = app.batteryRepo.snapshot()
        binding.tvStatus.text = getString(
            R.string.charge_status,
            if (snap.isCharging) getString(R.string.home_charging) else getString(R.string.home_discharging)
        )
        binding.tvTempAlert.text = getString(R.string.charge_temp_alert, snap.temperatureC)

        // 温度告警：触发条件是真实温度（不是用户操作），因此必须自带冷却去重
        if (snap.temperatureC >= tempThresholdC) {
            reportTempAlert(snap, tempThresholdC)
        }

        checkLimitAndAlert(app.prefs.chargeLimitPercent)
    }

    private fun checkLimitAndAlert(limit: Int) {
        val snap = app.batteryRepo.snapshot()
        if (snap.isCharging && snap.levelPercent >= limit) {
            reportLimitTriggered(limit, snap)
        }
    }

    // ------------------------------------------------------------ Smart Limit

    private fun refreshSmartLimitSuggestion() {
        val suggestion = smartLimitAdvisor.getSuggestion()

        binding.tvSuggestedLimit.text = getString(R.string.smart_limit_suggested, suggestion.suggestedLimit)

        val reasonText = when (suggestion.reason) {
            SmartLimitAdvisor.Suggestion.Reason.HEALTH_BASED ->
                getString(R.string.smart_limit_reason_health)
            SmartLimitAdvisor.Suggestion.Reason.HIGH_TEMP_DETECTED ->
                getString(R.string.smart_limit_reason_temp)
            SmartLimitAdvisor.Suggestion.Reason.DEFAULT_RECOMMENDATION ->
                getString(R.string.smart_limit_reason_default)
        }
        binding.tvSuggestedReason.text = reasonText

        updateSmartLimitDifferentWarning()
    }

    private fun updateSmartLimitDifferentWarning() {
        val isDifferent = smartLimitAdvisor.isCurrentLimitDifferentFromSuggestion()
        binding.tvLimitDifferent.visibility = if (isDifferent) View.VISIBLE else View.GONE
    }

    private fun applySuggestedLimit() {
        val suggestion = smartLimitAdvisor.getSuggestion()
        val newLimit = suggestion.suggestedLimit

        app.prefs.chargeLimitPercent = newLimit
        binding.sliderLimit.value = newLimit.toFloat()
        binding.tvLimit.text = getString(R.string.charge_limit_label, newLimit)

        updateSmartLimitDifferentWarning()

        Analytics.track(
            "ai_smart_limit_apply",
            mapOf(
                "suggested_limit" to newLimit,
                "reason" to suggestion.reason.name,
                "confidence" to suggestion.confidence.name
            )
        )

        Snackbar.make(
            requireView(),
            getString(R.string.charge_limit_label, newLimit),
            Snackbar.LENGTH_SHORT
        ).show()
    }

    // ------------------------------------------------------------ 校准

    private fun startCalibration() {
        Analytics.track(
            Dictionary.Event.CALIBRATION_STARTED,
            mapOf("method" to Dictionary.Method.GUIDED)
        )

        app.prefs.calibrationStartedAt = System.currentTimeMillis()
        enterStep(Dictionary.CalibrationStep.DISCHARGE)
    }

    private fun restoreCalibration() {
        val step = app.prefs.calibrationStep
        if (step.isBlank()) return
        enterStep(step)
    }

    private fun enterStep(step: String) {
        app.prefs.calibrationStep = step

        val guidance = app.calibrationCoach.getGuidanceForStep(step)

        binding.tvCalibrationStep.text = "Step ${guidance.stepNumber}/3: ${guidance.title}"
        binding.tvCalibrationWhy.text = guidance.whyItMatters
        binding.tvCalibrationTime.text = "${getString(R.string.calibration_time)}: ${guidance.estimatedTime}"

        if (guidance.recoveryHint != null) {
            binding.tvCalibrationTip.visibility = View.VISIBLE
            binding.tvCalibrationTip.text = guidance.recoveryHint
        } else {
            binding.tvCalibrationTip.visibility = View.GONE
        }

        val buttonText = when (step) {
            Dictionary.CalibrationStep.VERIFY -> getString(R.string.charge_calibration_finish)
            else -> if (guidance.canSkipTo) "Continue" else getString(R.string.charge_calibration_next)
        }
        binding.btnCalibrationNext.text = buttonText

        binding.calibrationPanel.visibility = View.VISIBLE
        binding.btnCalibrate.visibility = View.GONE

        Analytics.track(
            "ai_calibration_coach_step",
            mapOf(
                "step" to step,
                "can_skip" to guidance.canSkipTo
            )
        )
    }

    private fun advanceCalibration(view: View) {
        when (app.prefs.calibrationStep) {
            Dictionary.CalibrationStep.DISCHARGE ->
                enterStep(Dictionary.CalibrationStep.FULL_CHARGE)
            Dictionary.CalibrationStep.FULL_CHARGE ->
                enterStep(Dictionary.CalibrationStep.VERIFY)
            else -> finishCalibration(view)
        }
    }

    private fun finishCalibration(view: View) {
        val snap = app.batteryRepo.snapshot()

        app.prefs.lastCalibrationCompletedAt = System.currentTimeMillis()

        val crowdsourceData = if (app.prefs.calibrationCrowdsourceOptIn) {
            mapOf(
                "device_model" to android.os.Build.MODEL,
                "measured_capacity_mah" to snap.capacityMah,
                "health_score" to snap.healthScore
            )
        } else {
            emptyMap()
        }

        Analytics.track(
            Dictionary.Event.CALIBRATION_COMPLETED,
            mapOf(
                "cycles_completed" to 1,
                "measured_capacity_mah" to snap.capacityMah,
                "deviation_percent" to 0.0,
                "crowdsource_opted_in" to app.prefs.calibrationCrowdsourceOptIn
            ) + crowdsourceData
        )

        app.prefs.clearCalibration()
        resetCalibrationUi()

        // 校准完成后展示插屏。返回 false 表示不展示（未预加载/熔断/超限），
        // 此时不打断用户流程。
        activity?.let { act ->
            val shown = app.ads.showInterstitial(act) {
                Snackbar.make(view, R.string.monitor_title, Snackbar.LENGTH_SHORT).show()
            }
            if (!shown) {
                Snackbar.make(view, R.string.calibration_done, Snackbar.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 放弃校准。
     *
     * @param reason [Dictionary.CalibrationAbandonReason] 中的取值
     */
    private fun abandonCalibration(reason: String) {
        val step = app.prefs.calibrationStep
        if (step.isBlank()) return

        Analytics.track(
            Dictionary.Event.CALIBRATION_ABANDONED,
            mapOf(
                "step" to step,
                "reason" to reason
            )
        )

        app.prefs.clearCalibration()
        resetCalibrationUi()
    }

    private fun resetCalibrationUi() {
        if (_binding == null) return
        binding.calibrationPanel.visibility = View.GONE
        binding.btnCalibrate.visibility = View.VISIBLE
    }

    override fun onDestroyView() {
        // Activity 正在销毁（不是旋转屏幕等配置变更）时，进行中的校准视为被中断。
        // 配置变更会立即重建 Fragment，此时放弃是误报。
        val finishing = activity?.isFinishing == true
        val configChange = activity?.isChangingConfigurations == true

        if (finishing && !configChange) {
            abandonCalibration(Dictionary.CalibrationAbandonReason.APP_KILLED)
        }

        _binding = null
        super.onDestroyView()
    }
}
