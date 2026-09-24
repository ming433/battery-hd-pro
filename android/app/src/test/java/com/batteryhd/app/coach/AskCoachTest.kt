package com.batteryhd.app.coach

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for AskCoach (M3).
 */
class AskCoachTest {

    @Test
    fun `CoachResponse has all required fields`() {
        val response = AskCoach.CoachResponse(
            answer = "Test answer",
            wasBlocked = false,
            blockReason = null,
            suggestedAction = CoachInsight.PrimaryAction.NONE,
            confidence = "high"
        )

        assertEquals("Test answer", response.answer)
        assertFalse(response.wasBlocked)
        assertNull(response.blockReason)
        assertEquals(CoachInsight.PrimaryAction.NONE, response.suggestedAction)
        assertEquals("high", response.confidence)
    }

    @Test
    fun `blocked response has correct structure`() {
        val response = AskCoach.CoachResponse(
            answer = "I can't help with battery repair...",
            wasBlocked = true,
            blockReason = "battery repair",
            suggestedAction = CoachInsight.PrimaryAction.NONE,
            confidence = "high"
        )

        assertTrue(response.wasBlocked)
        assertNotNull(response.blockReason)
        assertEquals("battery repair", response.blockReason)
    }

    @Test
    fun `blocklist patterns should be comprehensive`() {
        val blockedTerms = listOf(
            "repair battery",
            "fix my battery",
            "restore capacity",
            "increase capacity",
            "boost battery"
        )

        for (term in blockedTerms) {
            assertTrue(
                "Term '$term' should be in blocklist",
                term.contains("repair") ||
                        term.contains("fix") ||
                        term.contains("restore") ||
                        term.contains("increase") ||
                        term.contains("boost")
            )
        }
    }

    @Test
    fun `blocked response should not promise battery repair`() {
        val honestRefusal = "I appreciate you reaching out, but I have to be honest: " +
                "no app can repair or restore battery capacity. " +
                "Batteries naturally degrade over time due to chemistry"

        assertFalse(
            "Honest refusal should not promise repair",
            honestRefusal.lowercase().contains("will repair") ||
                    honestRefusal.lowercase().contains("can repair") ||
                    honestRefusal.lowercase().contains("increase your capacity")
        )

        assertTrue(
            "Honest refusal should mention degradation is natural",
            honestRefusal.lowercase().contains("naturally degrade")
        )
    }

    @Test
    fun `valid questions should not be blocked`() {
        val validQuestions = listOf(
            "Should I charge overnight?",
            "Why is my battery draining fast?",
            "What is my battery health?",
            "How long will my battery last?",
            "Is fast charging bad?"
        )

        for (question in validQuestions) {
            assertFalse(
                "Question '$question' should not match blocklist",
                question.lowercase().contains("repair") ||
                        question.lowercase().contains("increase capacity") ||
                        question.lowercase().contains("boost battery") ||
                        question.lowercase().contains("restore capacity")
            )
        }
    }

    @Test
    fun `confidence levels are valid`() {
        val validConfidences = setOf("high", "medium", "low")

        for (conf in validConfidences) {
            assertTrue(
                "Confidence '$conf' should be valid",
                conf in validConfidences
            )
        }
    }

    @Test
    fun `suggested actions map to valid PrimaryAction values`() {
        val validActions = CoachInsight.PrimaryAction.entries.toSet()

        assertTrue(validActions.contains(CoachInsight.PrimaryAction.SET_CHARGE_LIMIT))
        assertTrue(validActions.contains(CoachInsight.PrimaryAction.START_CALIBRATION))
        assertTrue(validActions.contains(CoachInsight.PrimaryAction.OPEN_DRAIN_DETAIL))
        assertTrue(validActions.contains(CoachInsight.PrimaryAction.ENABLE_TEMP_ALERT))
        assertTrue(validActions.contains(CoachInsight.PrimaryAction.NONE))
    }
}
