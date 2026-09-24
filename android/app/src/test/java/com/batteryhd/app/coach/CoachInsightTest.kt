package com.batteryhd.app.coach

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CoachInsight model and blocklist compliance.
 */
class CoachInsightTest {

    @Test
    fun `noHistory returns insight with NONE action`() {
        val insight = CoachInsight.noHistory()
        
        assertEquals(CoachInsight.PrimaryAction.NONE, insight.primaryAction)
        assertEquals(CoachInsight.Confidence.LOW, insight.confidence)
        assertFalse(insight.headline.isBlank())
        assertFalse(insight.summary.isBlank())
    }

    @Test
    fun `insight summary must not contain blocklisted terms`() {
        val blocklist = listOf(
            "repair battery",
            "increase capacity",
            "boost battery",
            "fix battery",
            "restore capacity",
            "battery repair",
            "capacity increase"
        )
        
        val testInsights = listOf(
            CoachInsight.noHistory(),
            CoachInsight(
                headline = "Test headline",
                summary = "Test summary about charging habits",
                primaryAction = CoachInsight.PrimaryAction.SET_CHARGE_LIMIT,
                confidence = CoachInsight.Confidence.HIGH
            ),
            CoachInsight(
                headline = "Battery health needs attention",
                summary = "Your battery health is showing some decline. Running a calibration cycle can improve accuracy.",
                primaryAction = CoachInsight.PrimaryAction.START_CALIBRATION,
                confidence = CoachInsight.Confidence.MEDIUM
            )
        )

        for (insight in testInsights) {
            val lowerHeadline = insight.headline.lowercase()
            val lowerSummary = insight.summary.lowercase()
            
            for (term in blocklist) {
                assertFalse(
                    "Headline should not contain '$term': ${insight.headline}",
                    lowerHeadline.contains(term)
                )
                assertFalse(
                    "Summary should not contain '$term': ${insight.summary}",
                    lowerSummary.contains(term)
                )
            }
        }
    }

    @Test
    fun `PrimaryAction enum has expected values`() {
        val expectedActions = setOf(
            CoachInsight.PrimaryAction.SET_CHARGE_LIMIT,
            CoachInsight.PrimaryAction.START_CALIBRATION,
            CoachInsight.PrimaryAction.OPEN_DRAIN_DETAIL,
            CoachInsight.PrimaryAction.ENABLE_TEMP_ALERT,
            CoachInsight.PrimaryAction.NONE
        )
        
        assertEquals(expectedActions, CoachInsight.PrimaryAction.entries.toSet())
    }

    @Test
    fun `Confidence enum has expected values`() {
        val expectedConfidences = setOf(
            CoachInsight.Confidence.HIGH,
            CoachInsight.Confidence.MEDIUM,
            CoachInsight.Confidence.LOW
        )
        
        assertEquals(expectedConfidences, CoachInsight.Confidence.entries.toSet())
    }

    @Test
    fun `insight headline should be within character limit`() {
        val insight = CoachInsight(
            headline = "Battery looks excellent",
            summary = "Test summary",
            primaryAction = CoachInsight.PrimaryAction.NONE,
            confidence = CoachInsight.Confidence.HIGH
        )
        
        assertTrue(
            "Headline should be ≤60 chars: ${insight.headline.length}",
            insight.headline.length <= 60
        )
    }

    @Test
    fun `offline insight preserves data with isOffline flag`() {
        val original = CoachInsight(
            headline = "Test headline",
            summary = "Test summary",
            primaryAction = CoachInsight.PrimaryAction.SET_CHARGE_LIMIT,
            confidence = CoachInsight.Confidence.HIGH,
            isOffline = false
        )

        val offline = original.copy(isOffline = true)

        assertEquals(original.headline, offline.headline)
        assertEquals(original.summary, offline.summary)
        assertEquals(original.primaryAction, offline.primaryAction)
        assertEquals(original.confidence, offline.confidence)
        assertTrue(offline.isOffline)
        assertFalse(original.isOffline)
    }
}
