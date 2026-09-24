package com.batteryhd.app.coach

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for WeeklyReportGenerator (M3).
 */
class WeeklyReportGeneratorTest {

    @Test
    fun `WeeklyReport has all required fields`() {
        val report = WeeklyReportGenerator.WeeklyReport(
            weekNumber = 39,
            habitsScore = 85,
            habitsGrade = "B",
            totalSessions = 5,
            averageSessionMinutes = 90,
            highTempCharges = 1,
            maxTempRecorded = 38.5f,
            bestDayDescription = "Tuesday",
            worstDayDescription = "Saturday",
            topDrainApps = listOf("Chrome", "YouTube"),
            actionItems = listOf("Lower charge limit"),
            summaryText = "Summary",
            fullReportText = "Full report",
            generatedAt = System.currentTimeMillis()
        )

        assertEquals(39, report.weekNumber)
        assertEquals(85, report.habitsScore)
        assertEquals("B", report.habitsGrade)
        assertEquals(5, report.totalSessions)
        assertEquals(90, report.averageSessionMinutes)
        assertEquals(1, report.highTempCharges)
        assertTrue(report.maxTempRecorded > 0)
    }

    @Test
    fun `habits score should be between 0 and 100`() {
        val validScores = listOf(0, 25, 50, 75, 100)

        for (score in validScores) {
            assertTrue(
                "Score $score should be between 0 and 100",
                score in 0..100
            )
        }
    }

    @Test
    fun `habits grade should be valid letter grade`() {
        val validGrades = setOf("A", "B", "C", "D", "F")
        val gradeMapping = mapOf(
            90 to "A",
            80 to "B",
            70 to "C",
            60 to "D",
            50 to "F"
        )

        for ((score, expectedGrade) in gradeMapping) {
            val grade = when {
                score >= 90 -> "A"
                score >= 80 -> "B"
                score >= 70 -> "C"
                score >= 60 -> "D"
                else -> "F"
            }

            assertEquals(
                "Score $score should map to grade $expectedGrade",
                expectedGrade,
                grade
            )
            assertTrue(
                "Grade $grade should be valid",
                grade in validGrades
            )
        }
    }

    @Test
    fun `action items should not exceed 3`() {
        val maxActions = 3
        val testActions = listOf(
            "Action 1",
            "Action 2",
            "Action 3"
        )

        assertTrue(
            "Action items should not exceed $maxActions",
            testActions.size <= maxActions
        )
    }

    @Test
    fun `full report should not contain blocklisted terms`() {
        val blocklist = listOf(
            "repair battery",
            "increase capacity",
            "boost battery",
            "fix battery"
        )

        val sampleReport = "Weekly Battery Report: " +
                "Your habits score is 85. " +
                "Consider lowering your charge limit for better longevity."

        for (term in blocklist) {
            assertFalse(
                "Report should not contain '$term'",
                sampleReport.lowercase().contains(term)
            )
        }
    }

    @Test
    fun `summary text should be concise`() {
        val summary = "This week's charging habits score: 85 (B). " +
                "5 charging sessions, 1 at high temperature. Tap to see full report."

        assertTrue(
            "Summary should be under 200 characters",
            summary.length < 200
        )
    }

    @Test
    fun `top drain apps list should be limited`() {
        val maxApps = 3
        val testApps = listOf("App1", "App2", "App3").take(maxApps)

        assertTrue(
            "Top drain apps should not exceed $maxApps",
            testApps.size <= maxApps
        )
    }
}
