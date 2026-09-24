package com.batteryhd.app.ui.charge

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.batteryhd.analytics.Analytics
import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.BatteryHdApp
import com.batteryhd.app.R
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

    private val suggestedLimit = 80

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        _binding = FragmentChargeBinding.bind(view)

        val limit = app.prefs.chargeLimitPercent
        binding.sliderLimit.value = limit.toFloat()
        binding.tvLimit.text = getString(R.string.charge_limit_label, limit)

        binding.sliderLimit.addOnChangeListener { _, value, fromUser ->
            val percent = value.toInt()
            app.prefs.chargeLimitPercent = percent
            binding.tvLimit.text = getString(R.string.charge_limit_label, percent)
            updateSuggestionVisibility(percent)
            if (fromUser) checkLimitAndAlert(percent)
        }

        binding.tvSuggested.text = getString(R.string.charge_suggested, suggestedLimit)

        binding.btnApplySuggestion.setOnClickListener {
            binding.sliderLimit.value = suggestedLimit.toFloat()
            app.prefs.chargeLimitPercent = suggestedLimit
            binding.tvLimit.text = getString(R.string.charge_limit_label, suggestedLimit)
            updateSuggestionVisibility(suggestedLimit)
            Analytics.track(
                Dictionary.Event.SMART_LIMIT_APPLIED,
                mapOf("suggested_limit" to suggestedLimit, "previous_limit" to limit)
            )
        }

        binding.btnCalibrate.setOnClickListener { startCalibration() }
        binding.btnCalibrationNext.setOnClickListener { advanceCalibration(view) }
        binding.btnCalibrationCancel.setOnClickListener {
            abandonCalibration(Dictionary.CalibrationAbandonReason.USER_CANCEL)
            Snackbar.make(view, R.string.charge_calibration_abandoned, Snackbar.LENGTH_SHORT).show()
        }

        restoreCalibration()
        updateSuggestionVisibility(limit)
        refresh()
    }

    private fun updateSuggestionVisibility(currentLimit: Int) {
        binding.tvDifferentFromCoach.visibility = if (currentLimit != suggestedLimit) {
            View.VISIBLE
        } else {
            View.GONE
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

        val (textRes, buttonRes) = when (step) {
            Dictionary.CalibrationStep.DISCHARGE ->
                R.string.charge_calibration_step_discharge to R.string.charge_calibration_next
            Dictionary.CalibrationStep.FULL_CHARGE ->
                R.string.charge_calibration_step_charge to R.string.charge_calibration_next
            else ->
                R.string.charge_calibration_step_verify to R.string.charge_calibration_finish
        }

        binding.tvCalibrationStep.text = getString(textRes)
        binding.btnCalibrationNext.text = getString(buttonRes)
        binding.calibrationPanel.visibility = View.VISIBLE
        binding.btnCalibrate.visibility = View.GONE
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

        Analytics.track(
            Dictionary.Event.CALIBRATION_COMPLETED,
            mapOf(
                "cycles_completed" to 1,
                "measured_capacity_mah" to snap.capacityMah,
                "deviation_percent" to 0.0
            )
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
