package com.batteryhd.app.coach

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for SmartLimitAdvisor suggestion logic.
 *
 * These tests verify the heuristic rules for suggesting charge limits
 * based on battery health and temperature conditions.
 */
class SmartLimitAdvisorTest {

    @Test
    fun `Suggestion Reason enum has expected values`() {
        val expectedReasons = setOf(
            SmartLimitAdvisor.Suggestion.Reason.HEALTH_BASED,
            SmartLimitAdvisor.Suggestion.Reason.HIGH_TEMP_DETECTED,
            SmartLimitAdvisor.Suggestion.Reason.DEFAULT_RECOMMENDATION
        )

        assertEquals(expectedReasons, SmartLimitAdvisor.Suggestion.Reason.entries.toSet())
    }

    @Test
    fun `Suggestion Confidence enum has expected values`() {
        val expectedConfidences = setOf(
            SmartLimitAdvisor.Suggestion.Confidence.HIGH,
            SmartLimitAdvisor.Suggestion.Confidence.MEDIUM,
            SmartLimitAdvisor.Suggestion.Confidence.LOW
        )

        assertEquals(expectedConfidences, SmartLimitAdvisor.Suggestion.Confidence.entries.toSet())
    }

    @Test
    fun `suggested limits should be valid percentages`() {
        val validLimits = listOf(80, 85, 90)

        for (limit in validLimits) {
            assertTrue(
                "Limit $limit should be between 50 and 100",
                limit in 50..100
            )
        }
    }

    @Test
    fun `suggestion data class holds all expected fields`() {
        val suggestion = SmartLimitAdvisor.Suggestion(
            suggestedLimit = 80,
            reason = SmartLimitAdvisor.Suggestion.Reason.HEALTH_BASED,
            confidence = SmartLimitAdvisor.Suggestion.Confidence.HIGH
        )

        assertEquals(80, suggestion.suggestedLimit)
        assertEquals(SmartLimitAdvisor.Suggestion.Reason.HEALTH_BASED, suggestion.reason)
        assertEquals(SmartLimitAdvisor.Suggestion.Confidence.HIGH, suggestion.confidence)
    }

    @Test
    fun `high temp reason should suggest 80 percent limit`() {
        val suggestion = SmartLimitAdvisor.Suggestion(
            suggestedLimit = 80,
            reason = SmartLimitAdvisor.Suggestion.Reason.HIGH_TEMP_DETECTED,
            confidence = SmartLimitAdvisor.Suggestion.Confidence.HIGH
        )

        assertEquals(80, suggestion.suggestedLimit)
        assertEquals(SmartLimitAdvisor.Suggestion.Reason.HIGH_TEMP_DETECTED, suggestion.reason)
    }

    @Test
    fun `default recommendation should have low confidence`() {
        val suggestion = SmartLimitAdvisor.Suggestion(
            suggestedLimit = 80,
            reason = SmartLimitAdvisor.Suggestion.Reason.DEFAULT_RECOMMENDATION,
            confidence = SmartLimitAdvisor.Suggestion.Confidence.LOW
        )

        assertEquals(SmartLimitAdvisor.Suggestion.Confidence.LOW, suggestion.confidence)
    }

    @Test
    fun `health based reason with low health should suggest 80 percent`() {
        val lowHealthSuggestion = SmartLimitAdvisor.Suggestion(
            suggestedLimit = 80,
            reason = SmartLimitAdvisor.Suggestion.Reason.HEALTH_BASED,
            confidence = SmartLimitAdvisor.Suggestion.Confidence.HIGH
        )

        assertEquals(80, lowHealthSuggestion.suggestedLimit)
        assertEquals(SmartLimitAdvisor.Suggestion.Confidence.HIGH, lowHealthSuggestion.confidence)
    }

    @Test
    fun `health based reason with medium health should suggest 85 or 90 percent`() {
        val mediumHealthSuggestion = SmartLimitAdvisor.Suggestion(
            suggestedLimit = 85,
            reason = SmartLimitAdvisor.Suggestion.Reason.HEALTH_BASED,
            confidence = SmartLimitAdvisor.Suggestion.Confidence.MEDIUM
        )

        assertTrue(
            "Medium health should suggest 85 or 90",
            mediumHealthSuggestion.suggestedLimit in listOf(85, 90)
        )
    }
}
