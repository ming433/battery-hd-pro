package com.batteryhd.app.coach

import com.batteryhd.app.battery.BatteryRepository
import com.batteryhd.app.util.Prefs
import java.util.Calendar

/**
 * Tonight's Charge Plan advisor (M2).
 *
 * Generates a charging plan based on:
 * - User's desired ready-by time
 * - Current battery level
 * - Typical charging speed estimates
 * - Temperature considerations
 */
class ChargePlanAdvisor(
    private val batteryRepo: BatteryRepository,
    private val prefs: Prefs
) {

    data class ChargePlan(
        val targetPercent: Int,
        val suggestedStartHour: Int,
        val suggestedStartMinute: Int,
        val estimatedDurationMinutes: Int,
        val heatPauseGuidance: String?,
        val summary: String,
        val readyByHour: Int,
        val readyByMinute: Int
    )

    data class PlanInput(
        val readyByHour: Int,
        val readyByMinute: Int,
        val targetPercent: Int = 80
    )

    fun generatePlan(input: PlanInput): ChargePlan {
        val snap = batteryRepo.snapshot()
        val currentLevel = snap.levelPercent
        val currentTemp = snap.temperatureC
        val maxRecentTemp = prefs.chargeSessionMaxTempC

        val targetPercent = input.targetPercent.coerceIn(currentLevel, 100)
        val percentNeeded = targetPercent - currentLevel

        val estimatedMinutes = estimateChargingTime(percentNeeded, snap.chargerType)
        val (startHour, startMinute) = calculateStartTime(
            input.readyByHour,
            input.readyByMinute,
            estimatedMinutes
        )

        val heatGuidance = when {
            currentTemp >= 40f -> "Battery is warm right now. Wait until it cools below 35°C before charging."
            maxRecentTemp >= 42f -> "Recent high-temp charging detected. Use a slower charger if available."
            else -> null
        }

        val summary = buildSummary(
            currentLevel,
            targetPercent,
            startHour,
            startMinute,
            estimatedMinutes,
            heatGuidance != null
        )

        return ChargePlan(
            targetPercent = targetPercent,
            suggestedStartHour = startHour,
            suggestedStartMinute = startMinute,
            estimatedDurationMinutes = estimatedMinutes,
            heatPauseGuidance = heatGuidance,
            summary = summary,
            readyByHour = input.readyByHour,
            readyByMinute = input.readyByMinute
        )
    }

    private fun estimateChargingTime(percentNeeded: Int, chargerType: String): Int {
        if (percentNeeded <= 0) return 0

        val minutesPerPercent = when (chargerType) {
            "ac" -> 1.2f
            "usb" -> 2.5f
            "wireless" -> 2.0f
            else -> 1.5f
        }

        return (percentNeeded * minutesPerPercent).toInt().coerceAtLeast(10)
    }

    private fun calculateStartTime(
        readyByHour: Int,
        readyByMinute: Int,
        durationMinutes: Int
    ): Pair<Int, Int> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, readyByHour)
        cal.set(Calendar.MINUTE, readyByMinute)

        val bufferMinutes = 15
        cal.add(Calendar.MINUTE, -(durationMinutes + bufferMinutes))

        return cal.get(Calendar.HOUR_OF_DAY) to cal.get(Calendar.MINUTE)
    }

    private fun buildSummary(
        currentLevel: Int,
        targetPercent: Int,
        startHour: Int,
        startMinute: Int,
        durationMinutes: Int,
        hasHeatWarning: Boolean
    ): String {
        val startTime = String.format("%02d:%02d", startHour, startMinute)
        val hours = durationMinutes / 60
        val mins = durationMinutes % 60
        val durationStr = if (hours > 0) "${hours}h ${mins}m" else "${mins}m"

        val base = "Currently at $currentLevel%. Start charging around $startTime to reach $targetPercent% " +
                "(estimated $durationStr)."

        return if (hasHeatWarning) {
            "$base Monitor temperature during charging."
        } else {
            base
        }
    }

    fun savePlan(plan: ChargePlan) {
        prefs.chargePlanTargetPercent = plan.targetPercent
        prefs.chargePlanReadyByHour = plan.readyByHour
        prefs.chargePlanReadyByMinute = plan.readyByMinute
        prefs.chargePlanCreatedAt = System.currentTimeMillis()
    }

    fun getSavedPlan(): ChargePlan? {
        val createdAt = prefs.chargePlanCreatedAt
        if (createdAt <= 0) return null

        val oneDayMs = 24 * 60 * 60 * 1000L
        if (System.currentTimeMillis() - createdAt > oneDayMs) {
            clearPlan()
            return null
        }

        return generatePlan(
            PlanInput(
                readyByHour = prefs.chargePlanReadyByHour,
                readyByMinute = prefs.chargePlanReadyByMinute,
                targetPercent = prefs.chargePlanTargetPercent
            )
        )
    }

    fun clearPlan() {
        prefs.clearChargePlan()
    }
}
