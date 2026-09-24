package com.batteryhd.app.coach

import com.batteryhd.app.battery.BatteryRepository
import com.batteryhd.app.util.Prefs

/**
 * Smart Limit advisor for suggesting optimal charge limits.
 *
 * Uses on-device heuristics based on:
 * - Battery health score
 * - Recent high-temperature charging detection
 * - Current charge limit setting
 */
class SmartLimitAdvisor(
    private val batteryRepo: BatteryRepository,
    private val prefs: Prefs
) {

    data class Suggestion(
        val suggestedLimit: Int,
        val reason: Reason,
        val confidence: Confidence
    ) {
        enum class Reason {
            HEALTH_BASED,
            HIGH_TEMP_DETECTED,
            DEFAULT_RECOMMENDATION
        }

        enum class Confidence {
            HIGH,
            MEDIUM,
            LOW
        }
    }

    fun getSuggestion(): Suggestion {
        val snap = batteryRepo.snapshot()
        val healthScore = snap.healthScore
        val currentTemp = snap.temperatureC
        val maxTempRecent = prefs.chargeSessionMaxTempC

        return when {
            maxTempRecent >= 40f || currentTemp >= 38f -> {
                Suggestion(
                    suggestedLimit = 80,
                    reason = Suggestion.Reason.HIGH_TEMP_DETECTED,
                    confidence = Suggestion.Confidence.HIGH
                )
            }
            healthScore < 60 -> {
                Suggestion(
                    suggestedLimit = 80,
                    reason = Suggestion.Reason.HEALTH_BASED,
                    confidence = Suggestion.Confidence.HIGH
                )
            }
            healthScore < 75 -> {
                Suggestion(
                    suggestedLimit = 85,
                    reason = Suggestion.Reason.HEALTH_BASED,
                    confidence = Suggestion.Confidence.MEDIUM
                )
            }
            healthScore < 85 -> {
                Suggestion(
                    suggestedLimit = 90,
                    reason = Suggestion.Reason.HEALTH_BASED,
                    confidence = Suggestion.Confidence.MEDIUM
                )
            }
            else -> {
                Suggestion(
                    suggestedLimit = 80,
                    reason = Suggestion.Reason.DEFAULT_RECOMMENDATION,
                    confidence = Suggestion.Confidence.LOW
                )
            }
        }
    }

    fun isCurrentLimitDifferentFromSuggestion(): Boolean {
        val currentLimit = prefs.chargeLimitPercent
        val suggestion = getSuggestion()
        return currentLimit != suggestion.suggestedLimit
    }
}
