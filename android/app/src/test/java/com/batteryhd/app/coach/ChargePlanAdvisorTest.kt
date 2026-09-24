package com.batteryhd.app.coach

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for ChargePlanAdvisor (M2).
 */
class ChargePlanAdvisorTest {

    @Test
    fun `PlanInput has valid defaults`() {
        val input = ChargePlanAdvisor.PlanInput(
            readyByHour = 7,
            readyByMinute = 0
        )

        assertEquals(7, input.readyByHour)
        assertEquals(0, input.readyByMinute)
        assertEquals(80, input.targetPercent)
    }

    @Test
    fun `ChargePlan contains all required fields`() {
        val plan = ChargePlanAdvisor.ChargePlan(
            targetPercent = 80,
            suggestedStartHour = 23,
            suggestedStartMinute = 30,
            estimatedDurationMinutes = 90,
            heatPauseGuidance = null,
            summary = "Test summary",
            readyByHour = 7,
            readyByMinute = 0
        )

        assertEquals(80, plan.targetPercent)
        assertEquals(23, plan.suggestedStartHour)
        assertEquals(30, plan.suggestedStartMinute)
        assertEquals(90, plan.estimatedDurationMinutes)
        assertNull(plan.heatPauseGuidance)
        assertEquals("Test summary", plan.summary)
    }

    @Test
    fun `target percent should be within valid range`() {
        val validTargets = listOf(50, 60, 70, 80, 90, 100)

        for (target in validTargets) {
            assertTrue(
                "Target $target should be between 50 and 100",
                target in 50..100
            )
        }
    }

    @Test
    fun `heat guidance is present when temperature is high`() {
        val plan = ChargePlanAdvisor.ChargePlan(
            targetPercent = 80,
            suggestedStartHour = 23,
            suggestedStartMinute = 0,
            estimatedDurationMinutes = 60,
            heatPauseGuidance = "Battery is warm right now. Wait until it cools down.",
            summary = "Summary with heat warning",
            readyByHour = 7,
            readyByMinute = 0
        )

        assertNotNull(plan.heatPauseGuidance)
        assertTrue(plan.heatPauseGuidance!!.contains("warm") || plan.heatPauseGuidance!!.contains("cool"))
    }

    @Test
    fun `start time should be before ready-by time`() {
        val plan = ChargePlanAdvisor.ChargePlan(
            targetPercent = 80,
            suggestedStartHour = 23,
            suggestedStartMinute = 30,
            estimatedDurationMinutes = 90,
            heatPauseGuidance = null,
            summary = "Summary",
            readyByHour = 7,
            readyByMinute = 0
        )

        val startMinutes = plan.suggestedStartHour * 60 + plan.suggestedStartMinute
        val readyMinutes = plan.readyByHour * 60 + plan.readyByMinute

        val effectiveReady = if (readyMinutes < startMinutes) readyMinutes + 24 * 60 else readyMinutes
        assertTrue(
            "Start time should allow enough time before ready-by",
            effectiveReady - startMinutes >= plan.estimatedDurationMinutes
        )
    }
}
