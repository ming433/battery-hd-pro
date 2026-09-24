package com.batteryhd.app.coach

import com.batteryhd.app.battery.BatteryRepository
import com.batteryhd.app.util.Prefs

/**
 * Ask Coach MVP (M3).
 *
 * Limited-domain Q&A grounded on device features JSON.
 * - Only answers battery/charging/temperature/drain questions
 * - Refuses repair/capacity-boost requests with honest template
 * - English-first
 */
class AskCoach(
    private val batteryRepo: BatteryRepository,
    private val prefs: Prefs,
    private val tempTracker: TemperatureTracker
) {

    data class CoachResponse(
        val answer: String,
        val wasBlocked: Boolean,
        val blockReason: String?,
        val suggestedAction: CoachInsight.PrimaryAction,
        val confidence: String
    )

    private val blockedPatterns = listOf(
        "repair" to "battery repair",
        "fix my battery" to "battery repair",
        "restore" to "capacity restore",
        "increase capacity" to "capacity increase",
        "boost battery" to "capacity boost",
        "make battery last longer" to "false promise",
        "extend battery life forever" to "false promise",
        "health" to null,
        "medical" to "off-topic",
        "invest" to "off-topic",
        "stock" to "off-topic"
    )

    fun ask(question: String): CoachResponse {
        val lowerQuestion = question.lowercase().trim()

        val blockReason = checkBlocklist(lowerQuestion)
        if (blockReason != null) {
            return buildBlockedResponse(blockReason)
        }

        return generateAnswer(lowerQuestion)
    }

    private fun checkBlocklist(question: String): String? {
        for ((pattern, reason) in blockedPatterns) {
            if (reason != null && question.contains(pattern)) {
                return reason
            }
        }
        return null
    }

    private fun buildBlockedResponse(reason: String): CoachResponse {
        val answer = when (reason) {
            "battery repair", "capacity restore", "capacity increase", "capacity boost" ->
                HONEST_REFUSAL_TEMPLATE

            "false promise" ->
                "I can't promise to extend battery life beyond normal limits. " +
                        "What I can do is help you develop better charging habits that preserve your battery's natural lifespan. " +
                        "Would you like tips on healthy charging?"

            "off-topic" ->
                "I'm your battery coach, so I can only help with battery, charging, and power questions. " +
                        "Try asking about charging habits, battery health, or power usage."

            else ->
                "I'm not able to help with that request. " +
                        "I can answer questions about your battery health, charging habits, and power consumption."
        }

        return CoachResponse(
            answer = answer,
            wasBlocked = true,
            blockReason = reason,
            suggestedAction = CoachInsight.PrimaryAction.NONE,
            confidence = "high"
        )
    }

    private fun generateAnswer(question: String): CoachResponse {
        val snap = batteryRepo.snapshot()
        val features = buildFeaturesJson()

        return when {
            question.contains("overnight") || question.contains("sleep") ->
                answerOvernightCharging(snap)

            question.contains("fast charg") ->
                answerFastCharging(snap)

            question.contains("temperature") || question.contains("hot") || question.contains("warm") ->
                answerTemperature(snap)

            question.contains("health") || question.contains("capacity") ->
                answerHealth(snap)

            question.contains("drain") || question.contains("losing") || question.contains("dropping") ->
                answerDrain(snap)

            question.contains("calibrat") ->
                answerCalibration(snap)

            question.contains("limit") || question.contains("80%") || question.contains("full") ->
                answerChargeLimit(snap)

            question.contains("how long") || question.contains("last") ->
                answerBatteryLife(snap)

            else ->
                answerGeneral(snap)
        }
    }

    private fun answerOvernightCharging(snap: BatteryRepository.Snapshot): CoachResponse {
        val hasLimit = prefs.chargeLimitPercent < 100

        val answer = if (hasLimit) {
            "Overnight charging is generally safe with your charge limit set to ${prefs.chargeLimitPercent}%. " +
                    "The phone will stop charging when it reaches that level. " +
                    "Just avoid charging in hot environments or under pillows."
        } else {
            "Overnight charging is okay occasionally, but keeping the battery at 100% for hours can cause slight wear over time. " +
                    "Consider setting a charge limit to 80% to reduce stress on the battery."
        }

        return CoachResponse(
            answer = answer,
            wasBlocked = false,
            blockReason = null,
            suggestedAction = if (hasLimit) CoachInsight.PrimaryAction.NONE else CoachInsight.PrimaryAction.SET_CHARGE_LIMIT,
            confidence = "high"
        )
    }

    private fun answerFastCharging(snap: BatteryRepository.Snapshot): CoachResponse {
        val currentTemp = snap.temperatureC

        val answer = buildString {
            append("Fast charging is convenient but generates more heat. ")
            if (currentTemp > 35) {
                append("Your battery is currently at ${String.format("%.1f", currentTemp)}°C, which is a bit warm. ")
                append("Consider using a slower charger occasionally to reduce wear.")
            } else {
                append("Your current temperature (${String.format("%.1f", currentTemp)}°C) is fine. ")
                append("If you notice the phone getting hot during fast charging, that's normal, but avoid gaming while charging.")
            }
        }

        return CoachResponse(
            answer = answer,
            wasBlocked = false,
            blockReason = null,
            suggestedAction = CoachInsight.PrimaryAction.NONE,
            confidence = "high"
        )
    }

    private fun answerTemperature(snap: BatteryRepository.Snapshot): CoachResponse {
        val temp = snap.temperatureC
        val maxTemp = tempTracker.getMaxTemp24h()

        val answer = buildString {
            append("Your battery is currently at ${String.format("%.1f", temp)}°C. ")
            when {
                temp >= 40 -> append("That's quite warm! Avoid charging until it cools down. ")
                temp >= 35 -> append("That's slightly elevated. Normal for active use, but keep an eye on it. ")
                temp <= 10 -> append("That's cold! Cold batteries charge slower and report inaccurate levels. ")
                else -> append("That's a healthy temperature range. ")
            }
            if (maxTemp > temp) {
                append("Highest in the past 24 hours: ${String.format("%.1f", maxTemp)}°C.")
            }
        }

        return CoachResponse(
            answer = answer,
            wasBlocked = false,
            blockReason = null,
            suggestedAction = if (temp >= 40) CoachInsight.PrimaryAction.ENABLE_TEMP_ALERT else CoachInsight.PrimaryAction.NONE,
            confidence = "high"
        )
    }

    private fun answerHealth(snap: BatteryRepository.Snapshot): CoachResponse {
        val health = snap.healthScore
        val capacity = snap.capacityMah

        val answer = buildString {
            append("Your battery health score is $health/100. ")
            when {
                health >= 90 -> append("That's excellent! Your battery is in great condition. ")
                health >= 75 -> append("That's good. Normal wear for a used battery. ")
                health >= 60 -> append("That's fair. You might notice reduced battery life. ")
                else -> append("That's below average. Consider a calibration or professional check. ")
            }
            if (capacity > 0) {
                append("Estimated capacity: ${capacity}mAh. ")
            }
            append("Note: This score is an estimate based on usage patterns, not a laboratory measurement.")
        }

        return CoachResponse(
            answer = answer,
            wasBlocked = false,
            blockReason = null,
            suggestedAction = if (health < 70) CoachInsight.PrimaryAction.START_CALIBRATION else CoachInsight.PrimaryAction.NONE,
            confidence = if (health >= 70) "high" else "medium"
        )
    }

    private fun answerDrain(snap: BatteryRepository.Snapshot): CoachResponse {
        val level = snap.levelPercent

        val answer = buildString {
            append("Your battery is at $level%. ")
            if (snap.isCharging) {
                append("Currently charging, so drain analysis isn't available right now. ")
            } else {
                append("To understand what's using power, check the Usage tab. ")
                append("Common drain causes: high screen brightness, GPS, mobile data in weak signal areas, and background apps.")
            }
        }

        return CoachResponse(
            answer = answer,
            wasBlocked = false,
            blockReason = null,
            suggestedAction = CoachInsight.PrimaryAction.OPEN_DRAIN_DETAIL,
            confidence = "medium"
        )
    }

    private fun answerCalibration(snap: BatteryRepository.Snapshot): CoachResponse {
        val answer = "Battery calibration helps the system accurately measure your battery's true capacity. " +
                "It involves draining to about 20%, then charging to 100% without interruption. " +
                "This doesn't repair or restore your battery — it just improves the accuracy of readings. " +
                "Good to do once every few months, or if the percentage seems inaccurate."

        return CoachResponse(
            answer = answer,
            wasBlocked = false,
            blockReason = null,
            suggestedAction = CoachInsight.PrimaryAction.START_CALIBRATION,
            confidence = "high"
        )
    }

    private fun answerChargeLimit(snap: BatteryRepository.Snapshot): CoachResponse {
        val currentLimit = prefs.chargeLimitPercent

        val answer = buildString {
            append("Your charge limit is set to $currentLimit%. ")
            if (currentLimit <= 80) {
                append("Great choice! Keeping the battery between 20-80% reduces chemical stress and extends lifespan. ")
            } else if (currentLimit <= 90) {
                append("That's a reasonable balance between convenience and longevity. ")
            } else {
                append("Charging to 100% is fine occasionally, but regularly keeping the battery full can accelerate wear. ")
                append("Consider lowering to 80-85% for everyday use.")
            }
        }

        return CoachResponse(
            answer = answer,
            wasBlocked = false,
            blockReason = null,
            suggestedAction = if (currentLimit > 85) CoachInsight.PrimaryAction.SET_CHARGE_LIMIT else CoachInsight.PrimaryAction.NONE,
            confidence = "high"
        )
    }

    private fun answerBatteryLife(snap: BatteryRepository.Snapshot): CoachResponse {
        val level = snap.levelPercent
        val estimates = batteryRepo.sceneEstimates(level)
        val standbyHours = estimates.find { it.first == "standby" }?.second ?: 0f

        val answer = buildString {
            append("At $level%, estimated standby time is about ${standbyHours.toInt()} hours. ")
            append("Active use varies by activity: video playback ~${(level / 12f).toInt()}h, gaming ~${(level / 20f).toInt()}h, browsing ~${(level / 8f).toInt()}h. ")
            append("These are estimates based on typical power consumption.")
        }

        return CoachResponse(
            answer = answer,
            wasBlocked = false,
            blockReason = null,
            suggestedAction = CoachInsight.PrimaryAction.NONE,
            confidence = "medium"
        )
    }

    private fun answerGeneral(snap: BatteryRepository.Snapshot): CoachResponse {
        val answer = buildString {
            append("I'm your battery coach! Currently: ${snap.levelPercent}% battery, ")
            append("${String.format("%.1f", snap.temperatureC)}°C, ")
            append(if (snap.isCharging) "charging" else "not charging")
            append(". ")
            append("You can ask me about:\n")
            append("• Overnight or fast charging\n")
            append("• Battery health and calibration\n")
            append("• Temperature concerns\n")
            append("• Why battery is draining\n")
            append("• Charge limits and best practices")
        }

        return CoachResponse(
            answer = answer,
            wasBlocked = false,
            blockReason = null,
            suggestedAction = CoachInsight.PrimaryAction.NONE,
            confidence = "high"
        )
    }

    private fun buildFeaturesJson(): String {
        val snap = batteryRepo.snapshot()
        return """
            {
                "battery_level": ${snap.levelPercent},
                "temperature_c": ${snap.temperatureC},
                "is_charging": ${snap.isCharging},
                "health_score": ${snap.healthScore},
                "capacity_mah": ${snap.capacityMah},
                "charge_limit": ${prefs.chargeLimitPercent},
                "weekly_sessions": ${prefs.weeklySessionCount}
            }
        """.trimIndent()
    }

    companion object {
        private const val HONEST_REFUSAL_TEMPLATE =
            "I appreciate you reaching out, but I have to be honest: no app can repair or restore battery capacity. " +
                    "Batteries naturally degrade over time due to chemistry — that's physics, not something software can fix. " +
                    "What I CAN help with is developing better charging habits to slow down future wear. " +
                    "Would you like tips on healthy charging practices instead?"
    }
}
