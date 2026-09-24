package com.batteryhd.app.coach

import android.app.usage.UsageStatsManager
import android.content.Context
import com.batteryhd.app.battery.BatteryRepository
import com.batteryhd.app.util.Prefs

/**
 * Drain Detective (M2).
 *
 * Analyzes battery drain patterns and provides plain-English explanations.
 * Compares recent drain against baseline to identify anomalies.
 *
 * Does NOT build wakelock killer features (per PRD constraints).
 */
class DrainDetective(
    private val context: Context,
    private val batteryRepo: BatteryRepository,
    private val prefs: Prefs
) {

    data class DrainAnalysis(
        val headline: String,
        val explanation: String,
        val drainPerHourPercent: Float,
        val baselinePerHourPercent: Float,
        val deviationPercent: Int,
        val topContributors: List<String>,
        val hasUsageAccess: Boolean,
        val confidence: Confidence
    ) {
        enum class Confidence { HIGH, MEDIUM, LOW }
    }

    fun analyze(): DrainAnalysis {
        val snap = batteryRepo.snapshot()
        val hasUsageAccess = hasUsageStatsPermission()

        val currentDrain = estimateCurrentDrainRate(snap)
        val baseline = getBaselineDrainRate()
        val deviation = if (baseline > 0) {
            ((currentDrain - baseline) / baseline * 100).toInt()
        } else {
            0
        }

        val topApps = if (hasUsageAccess) {
            getTopForegroundApps()
        } else {
            emptyList()
        }

        val (headline, explanation) = generateExplanation(
            currentDrain,
            baseline,
            deviation,
            topApps,
            snap.isCharging,
            hasUsageAccess
        )

        val confidence = when {
            prefs.weeklySessionCount < 3 -> DrainAnalysis.Confidence.LOW
            hasUsageAccess -> DrainAnalysis.Confidence.HIGH
            else -> DrainAnalysis.Confidence.MEDIUM
        }

        return DrainAnalysis(
            headline = headline,
            explanation = explanation,
            drainPerHourPercent = currentDrain,
            baselinePerHourPercent = baseline,
            deviationPercent = deviation,
            topContributors = topApps,
            hasUsageAccess = hasUsageAccess,
            confidence = confidence
        )
    }

    private fun estimateCurrentDrainRate(snap: BatteryRepository.Snapshot): Float {
        if (snap.isCharging) return 0f

        val sessionStart = prefs.chargeSessionStartedAt
        if (sessionStart <= 0) {
            return getBaselineDrainRate()
        }

        val startLevel = prefs.chargeSessionStartLevel
        val currentLevel = snap.levelPercent
        val elapsedMs = System.currentTimeMillis() - sessionStart

        if (elapsedMs < 60_000 || startLevel <= currentLevel) {
            return getBaselineDrainRate()
        }

        val elapsedHours = elapsedMs / (60f * 60f * 1000f)
        return (startLevel - currentLevel) / elapsedHours
    }

    private fun getBaselineDrainRate(): Float {
        return 8f
    }

    private fun generateExplanation(
        current: Float,
        baseline: Float,
        deviation: Int,
        topApps: List<String>,
        isCharging: Boolean,
        hasUsageAccess: Boolean
    ): Pair<String, String> {
        if (isCharging) {
            return "Battery is charging" to
                    "Drain analysis is available when your phone is unplugged and in use."
        }

        val headline = when {
            deviation > 50 -> "Unusually high battery drain"
            deviation > 20 -> "Battery draining faster than usual"
            deviation < -20 -> "Battery drain is lower than usual"
            else -> "Battery drain looks normal"
        }

        val explanation = buildString {
            append("Current drain rate: ~${current.toInt()}% per hour. ")

            if (deviation > 20) {
                append("That's about $deviation% higher than your typical usage. ")
            } else if (deviation < -20) {
                append("That's ${-deviation}% lower than usual — good! ")
            } else {
                append("This is within your normal range. ")
            }

            if (topApps.isNotEmpty()) {
                append("Top active apps: ${topApps.take(3).joinToString(", ")}. ")
            } else if (!hasUsageAccess) {
                append("Grant Usage Access to see which apps are using the most battery.")
            }

            if (current > 15) {
                append("High screen brightness, gaming, or video streaming can increase drain significantly.")
            }
        }

        return headline to explanation
    }

    private fun getTopForegroundApps(): List<String> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE)
                as? UsageStatsManager ?: return emptyList()

        val now = System.currentTimeMillis()
        val threeHoursAgo = now - 3 * 60 * 60 * 1000L

        val stats = runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, threeHoursAgo, now)
        }.getOrNull() ?: return emptyList()

        return stats
            .filter { it.totalTimeInForeground > 60_000 }
            .sortedByDescending { it.totalTimeInForeground }
            .take(5)
            .mapNotNull { stat ->
                runCatching {
                    val pm = context.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(stat.packageName, 0)).toString()
                }.getOrNull()
            }
    }

    private fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE)
                as? android.app.AppOpsManager ?: return false

        val mode = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                appOps.unsafeCheckOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            } else {
                @Suppress("DEPRECATION")
                appOps.checkOpNoThrow(
                    android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
                    android.os.Process.myUid(),
                    context.packageName
                )
            }
        }.getOrDefault(android.app.AppOpsManager.MODE_ERRORED)

        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    fun getWeakDiagnosis(): DrainAnalysis {
        val snap = batteryRepo.snapshot()

        val headline = if (snap.isCharging) {
            "Currently charging"
        } else {
            "Monitoring battery usage"
        }

        val explanation = if (snap.isCharging) {
            "Drain analysis will be available when you unplug your phone."
        } else {
            "Battery is at ${snap.levelPercent}%. Grant Usage Access permission to see detailed app-level power consumption and get personalized insights."
        }

        return DrainAnalysis(
            headline = headline,
            explanation = explanation,
            drainPerHourPercent = 0f,
            baselinePerHourPercent = getBaselineDrainRate(),
            deviationPercent = 0,
            topContributors = emptyList(),
            hasUsageAccess = false,
            confidence = DrainAnalysis.Confidence.LOW
        )
    }
}
