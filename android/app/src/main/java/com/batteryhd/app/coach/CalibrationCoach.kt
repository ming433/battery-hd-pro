package com.batteryhd.app.coach

import com.batteryhd.analytics.Dictionary
import com.batteryhd.app.battery.BatteryRepository

/**
 * Calibration Coach (M2).
 *
 * Provides coach-style guidance for the 3-step calibration process:
 * - Why each step matters
 * - Dynamic copy based on current battery level
 * - Failure recovery hints
 * - Estimated time for each step
 */
class CalibrationCoach(
    private val batteryRepo: BatteryRepository
) {

    data class StepGuidance(
        val stepNumber: Int,
        val title: String,
        val whyItMatters: String,
        val instruction: String,
        val estimatedTime: String,
        val recoveryHint: String?,
        val canSkipTo: Boolean
    )

    fun getGuidanceForStep(step: String): StepGuidance {
        val snap = batteryRepo.snapshot()
        val currentLevel = snap.levelPercent

        return when (step) {
            Dictionary.CalibrationStep.DISCHARGE -> getDischargeGuidance(currentLevel)
            Dictionary.CalibrationStep.FULL_CHARGE -> getFullChargeGuidance(currentLevel, snap.isCharging)
            Dictionary.CalibrationStep.VERIFY -> getVerifyGuidance()
            else -> getDischargeGuidance(currentLevel)
        }
    }

    private fun getDischargeGuidance(currentLevel: Int): StepGuidance {
        val canSkip = currentLevel <= 20

        val instruction = if (canSkip) {
            "Your battery is already at $currentLevel%, which is low enough. You can proceed to the next step."
        } else {
            "Use your phone normally until battery drops below 20%. Currently at $currentLevel%."
        }

        val estimatedTime = if (canSkip) {
            "Ready now"
        } else {
            val hoursNeeded = ((currentLevel - 20) / 8f).toInt().coerceAtLeast(1)
            "About $hoursNeeded-${hoursNeeded + 2} hours of normal use"
        }

        return StepGuidance(
            stepNumber = 1,
            title = "Discharge to 20%",
            whyItMatters = "Starting from a low charge helps the system measure the full capacity range accurately.",
            instruction = instruction,
            estimatedTime = estimatedTime,
            recoveryHint = "If you need to charge before reaching 20%, that's okay. Just restart calibration later.",
            canSkipTo = canSkip
        )
    }

    private fun getFullChargeGuidance(currentLevel: Int, isCharging: Boolean): StepGuidance {
        val statusNote = when {
            isCharging && currentLevel >= 95 -> "Almost there! Stay plugged in until 100%."
            isCharging -> "Charging... Currently at $currentLevel%."
            currentLevel >= 100 -> "Fully charged! You can proceed to verification."
            else -> "Plug in your charger and charge to 100% without interruption."
        }

        val estimatedTime = when {
            currentLevel >= 100 -> "Ready now"
            currentLevel >= 80 -> "About 20-40 minutes"
            currentLevel >= 50 -> "About 1-1.5 hours"
            else -> "About 1.5-2.5 hours"
        }

        return StepGuidance(
            stepNumber = 2,
            title = "Charge to 100%",
            whyItMatters = "A complete charge cycle from low to full helps calculate true battery capacity.",
            instruction = statusNote,
            estimatedTime = estimatedTime,
            recoveryHint = "If charging was interrupted (unplugged early), start over from step 1 for best accuracy.",
            canSkipTo = currentLevel >= 100
        )
    }

    private fun getVerifyGuidance(): StepGuidance {
        return StepGuidance(
            stepNumber = 3,
            title = "Verify Calibration",
            whyItMatters = "The system compares measured capacity against reported capacity to improve accuracy.",
            instruction = "Calculating battery capacity based on the charge cycle data...",
            estimatedTime = "A few seconds",
            recoveryHint = null,
            canSkipTo = true
        )
    }

    fun getFailureRecoveryMessage(failureReason: String): String {
        return when (failureReason) {
            Dictionary.CalibrationAbandonReason.USER_CANCEL ->
                "No problem! You can restart calibration anytime from the Charge tab."

            Dictionary.CalibrationAbandonReason.APP_KILLED ->
                "The app was closed during calibration. Your progress wasn't saved, but you can start again."

            Dictionary.CalibrationAbandonReason.TIMEOUT ->
                "Calibration took too long. This can happen if the battery wasn't fully discharged or charged. Try again with a complete cycle."

            Dictionary.CalibrationAbandonReason.CHARGE_INTERRUPTED ->
                "Charging was interrupted before reaching 100%. For accurate calibration, complete a full charge without unplugging."

            else ->
                "Something went wrong. Please try calibrating again when convenient."
        }
    }

    fun shouldSuggestCalibration(healthScore: Int, lastCalibrationAt: Long): Boolean {
        val thirtyDaysMs = 30L * 24 * 60 * 60 * 1000

        return when {
            healthScore < 70 -> true
            lastCalibrationAt <= 0 -> true
            System.currentTimeMillis() - lastCalibrationAt > thirtyDaysMs -> true
            else -> false
        }
    }
}
