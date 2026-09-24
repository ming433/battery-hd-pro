package com.batteryhd.app.coach

import com.batteryhd.app.battery.BatteryRepository
import com.batteryhd.app.util.Prefs

/**
 * Repository interface for generating AI coach insights.
 *
 * This abstraction allows:
 * - M1: Local rule-based coach using on-device signals
 * - Future: Cloud LLM-based coach with the same interface
 *
 * Implementations must never claim battery repair or capacity increase.
 */
interface CoachInsightRepository {
    fun generateInsight(): CoachInsight
    fun getCachedInsight(): CoachInsight?
    fun clearCache()
}

/**
 * Local rule-based coach implementation for M1.
 *
 * Aggregates on-device signals (battery level, charging state, temperature,
 * health score, recent charge sessions) to generate structured insights.
 */
class LocalCoachInsightRepository(
    private val batteryRepo: BatteryRepository,
    private val prefs: Prefs
) : CoachInsightRepository {

    @Volatile
    private var cachedInsight: CoachInsight? = null

    override fun generateInsight(): CoachInsight {
        val snap = batteryRepo.snapshot()
        val weeklySessionCount = prefs.weeklySessionCount
        val insight = generateFromSignals(snap, weeklySessionCount)
        cachedInsight = insight
        return insight
    }

    override fun getCachedInsight(): CoachInsight? = cachedInsight

    override fun clearCache() {
        cachedInsight = null
    }

    private fun generateFromSignals(
        snap: BatteryRepository.Snapshot,
        weeklySessionCount: Int
    ): CoachInsight {
        if (weeklySessionCount < 3) {
            return CoachInsight.noHistory()
        }

        val healthScore = snap.healthScore
        val temperatureC = snap.temperatureC
        val levelPercent = snap.levelPercent
        val isCharging = snap.isCharging
        val chargeLimit = prefs.chargeLimitPercent

        return when {
            temperatureC >= 40f && isCharging -> generateHighTempInsight(temperatureC, healthScore)
            healthScore < 60 -> generateLowHealthInsight(healthScore)
            isCharging && levelPercent >= 80 && chargeLimit > 85 -> generateOverchargeInsight(levelPercent, chargeLimit)
            healthScore < 75 && weeklySessionCount >= 5 -> generateCalibrationInsight(healthScore)
            levelPercent <= 15 && !isCharging -> generateLowBatteryInsight(levelPercent)
            else -> generateGoodStatusInsight(healthScore, levelPercent, isCharging)
        }
    }

    private fun generateHighTempInsight(temp: Float, health: Int): CoachInsight {
        return CoachInsight(
            headline = "Battery is running warm",
            summary = "Your battery temperature is ${String.format("%.1f", temp)}°C while charging. " +
                    "High temperatures during charging accelerate battery wear. " +
                    "Consider unplugging until it cools down, or use a slower charger.",
            primaryAction = CoachInsight.PrimaryAction.ENABLE_TEMP_ALERT,
            confidence = CoachInsight.Confidence.HIGH
        )
    }

    private fun generateLowHealthInsight(health: Int): CoachInsight {
        val severity = when {
            health < 40 -> "Your battery health is significantly reduced"
            health < 50 -> "Your battery health is moderately reduced"
            else -> "Your battery health is showing some decline"
        }
        return CoachInsight(
            headline = "Battery health needs attention",
            summary = "$severity (score: $health). " +
                    "Running a calibration cycle can improve accuracy of health readings. " +
                    "Avoid charging in hot environments and keep charge between 20-80% when possible.",
            primaryAction = CoachInsight.PrimaryAction.START_CALIBRATION,
            confidence = CoachInsight.Confidence.MEDIUM
        )
    }

    private fun generateOverchargeInsight(level: Int, currentLimit: Int): CoachInsight {
        return CoachInsight(
            headline = "Consider a lower charge limit",
            summary = "Your battery is at $level% and your limit is set to $currentLimit%. " +
                    "Keeping batteries between 20-80% can extend their lifespan. " +
                    "Try setting your charge limit to 80% for better long-term health.",
            primaryAction = CoachInsight.PrimaryAction.SET_CHARGE_LIMIT,
            confidence = CoachInsight.Confidence.HIGH
        )
    }

    private fun generateCalibrationInsight(health: Int): CoachInsight {
        return CoachInsight(
            headline = "Time for a calibration",
            summary = "Your health score is $health and you've been charging regularly. " +
                    "A full calibration cycle (drain to 20%, then charge to 100%) " +
                    "helps the system accurately measure your battery's true capacity.",
            primaryAction = CoachInsight.PrimaryAction.START_CALIBRATION,
            confidence = CoachInsight.Confidence.MEDIUM
        )
    }

    private fun generateLowBatteryInsight(level: Int): CoachInsight {
        return CoachInsight(
            headline = "Battery is getting low",
            summary = "Your battery is at $level%. For optimal battery health, try to keep " +
                    "charge above 20% when possible. Deep discharges can increase wear over time. " +
                    "Plug in when convenient.",
            primaryAction = CoachInsight.PrimaryAction.NONE,
            confidence = CoachInsight.Confidence.HIGH
        )
    }

    private fun generateGoodStatusInsight(health: Int, level: Int, isCharging: Boolean): CoachInsight {
        val chargingStatus = if (isCharging) "Currently charging" else "Not charging"
        val healthDesc = when {
            health >= 90 -> "excellent"
            health >= 75 -> "good"
            else -> "fair"
        }
        return CoachInsight(
            headline = "Battery looks $healthDesc",
            summary = "$chargingStatus at $level%. Your battery health score is $health, which is $healthDesc. " +
                    "Keep following good charging habits: avoid extreme temperatures, " +
                    "and unplug before reaching 100% when possible.",
            primaryAction = CoachInsight.PrimaryAction.NONE,
            confidence = CoachInsight.Confidence.HIGH
        )
    }
}
