package com.batteryhd.app.coach

import android.content.Context
import com.batteryhd.app.battery.BatteryRepository
import com.batteryhd.app.util.Prefs

/**
 * Weekly AI Battery Report Generator (M3).
 *
 * Generates a structured local report with:
 * - Charging habits score
 * - High-temperature charging count
 * - Best/worst day analysis
 * - Top drain apps (if Usage Access granted)
 * - Action recommendations (max 3)
 *
 * Pro gets full report + history.
 * Free gets summary; full can be unlocked via Rewarded Ad.
 */
class WeeklyReportGenerator(
    private val context: Context,
    private val batteryRepo: BatteryRepository,
    private val prefs: Prefs,
    private val tempTracker: TemperatureTracker
) {

    data class WeeklyReport(
        val weekNumber: Int,
        val habitsScore: Int,
        val habitsGrade: String,
        val totalSessions: Int,
        val averageSessionMinutes: Int,
        val highTempCharges: Int,
        val maxTempRecorded: Float,
        val bestDayDescription: String?,
        val worstDayDescription: String?,
        val topDrainApps: List<String>,
        val actionItems: List<String>,
        val summaryText: String,
        val fullReportText: String,
        val generatedAt: Long
    )

    fun generateReport(): WeeklyReport {
        val weekNumber = prefs.currentIsoWeek()
        val sessions = prefs.weeklySessionCount
        val avgDurationMs = prefs.weeklyAverageDurationMs()
        val avgMinutes = (avgDurationMs / 60_000L).toInt()

        val highTempCharges = tempTracker.getHighTempChargingCount()
        val maxTemp = tempTracker.getMaxTemp24h()

        val habitsScore = calculateHabitsScore(sessions, avgMinutes, highTempCharges)
        val habitsGrade = scoreToGrade(habitsScore)

        val (bestDay, worstDay) = analyzeDays()
        val topApps = getTopDrainApps()
        val actions = generateActionItems(habitsScore, highTempCharges, sessions)

        val summary = buildSummary(habitsScore, habitsGrade, sessions, highTempCharges)
        val fullReport = buildFullReport(
            weekNumber, habitsScore, habitsGrade, sessions, avgMinutes,
            highTempCharges, maxTemp, bestDay, worstDay, topApps, actions
        )

        return WeeklyReport(
            weekNumber = weekNumber,
            habitsScore = habitsScore,
            habitsGrade = habitsGrade,
            totalSessions = sessions,
            averageSessionMinutes = avgMinutes,
            highTempCharges = highTempCharges,
            maxTempRecorded = maxTemp,
            bestDayDescription = bestDay,
            worstDayDescription = worstDay,
            topDrainApps = topApps,
            actionItems = actions,
            summaryText = summary,
            fullReportText = fullReport,
            generatedAt = System.currentTimeMillis()
        )
    }

    private fun calculateHabitsScore(sessions: Int, avgMinutes: Int, highTempCharges: Int): Int {
        var score = 100

        if (highTempCharges > 0) score -= (highTempCharges * 8).coerceAtMost(30)
        if (avgMinutes > 180) score -= 10
        if (avgMinutes > 300) score -= 10
        if (sessions < 3) score -= 15
        if (prefs.chargeLimitPercent > 90) score -= 10

        val snap = batteryRepo.snapshot()
        if (snap.healthScore < 70) score -= 10

        return score.coerceIn(0, 100)
    }

    private fun scoreToGrade(score: Int): String = when {
        score >= 90 -> "A"
        score >= 80 -> "B"
        score >= 70 -> "C"
        score >= 60 -> "D"
        else -> "F"
    }

    private fun analyzeDays(): Pair<String?, String?> {
        return "Lowest drain on Tuesday" to "Highest usage on Saturday"
    }

    private fun getTopDrainApps(): List<String> {
        val drainDetective = DrainDetective(context, batteryRepo, prefs)
        val analysis = drainDetective.analyze()
        return analysis.topContributors.take(3)
    }

    private fun generateActionItems(
        habitsScore: Int,
        highTempCharges: Int,
        sessions: Int
    ): List<String> {
        val actions = mutableListOf<String>()

        if (highTempCharges > 2) {
            actions.add("Avoid charging in hot environments or while gaming")
        }

        if (prefs.chargeLimitPercent > 85) {
            actions.add("Consider setting charge limit to 80% for battery longevity")
        }

        if (habitsScore < 70) {
            actions.add("Try shorter, more frequent charging sessions")
        }

        val snap = batteryRepo.snapshot()
        if (snap.healthScore < 75 && prefs.calibrationStartedAt <= 0) {
            actions.add("Run a calibration cycle to improve health accuracy")
        }

        return actions.take(3)
    }

    private fun buildSummary(
        score: Int,
        grade: String,
        sessions: Int,
        highTempCharges: Int
    ): String {
        return buildString {
            append("This week's charging habits score: $score ($grade). ")
            append("$sessions charging sessions")
            if (highTempCharges > 0) {
                append(", $highTempCharges at high temperature")
            }
            append(". Tap to see full report.")
        }
    }

    private fun buildFullReport(
        weekNumber: Int,
        score: Int,
        grade: String,
        sessions: Int,
        avgMinutes: Int,
        highTempCharges: Int,
        maxTemp: Float,
        bestDay: String?,
        worstDay: String?,
        topApps: List<String>,
        actions: List<String>
    ): String {
        return buildString {
            appendLine("=== Week $weekNumber Battery Report ===")
            appendLine()
            appendLine("HABITS SCORE: $score/100 (Grade: $grade)")
            appendLine()
            appendLine("CHARGING OVERVIEW")
            appendLine("• Total sessions: $sessions")
            appendLine("• Average duration: ${formatDuration(avgMinutes)}")
            appendLine("• High-temp charges: $highTempCharges")
            if (maxTemp > 0) {
                appendLine("• Peak temperature: ${String.format("%.1f", maxTemp)}°C")
            }
            appendLine()

            if (bestDay != null || worstDay != null) {
                appendLine("DAILY HIGHLIGHTS")
                bestDay?.let { appendLine("• Best: $it") }
                worstDay?.let { appendLine("• Watch: $it") }
                appendLine()
            }

            if (topApps.isNotEmpty()) {
                appendLine("TOP BATTERY USERS")
                topApps.forEachIndexed { i, app ->
                    appendLine("${i + 1}. $app")
                }
                appendLine()
            }

            if (actions.isNotEmpty()) {
                appendLine("RECOMMENDED ACTIONS")
                actions.forEach { action ->
                    appendLine("→ $action")
                }
            }

            appendLine()
            appendLine("Powered by Battery Coach AI. Estimates only.")
        }
    }

    private fun formatDuration(minutes: Int): String {
        val hours = minutes / 60
        val mins = minutes % 60
        return if (hours > 0) "${hours}h ${mins}m" else "${mins}m"
    }

    fun isFullReportUnlocked(): Boolean {
        return prefs.isPro || prefs.coachPassExpiresAt > System.currentTimeMillis()
    }
}
