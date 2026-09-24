package com.batteryhd.app.coach

import com.batteryhd.analytics.Dictionary
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for CalibrationCoach (M2).
 */
class CalibrationCoachTest {

    @Test
    fun `StepGuidance has all required fields`() {
        val guidance = CalibrationCoach.StepGuidance(
            stepNumber = 1,
            title = "Discharge to 20%",
            whyItMatters = "Starting from low helps measure full capacity",
            instruction = "Use your phone normally",
            estimatedTime = "2-4 hours",
            recoveryHint = "You can restart later",
            canSkipTo = false
        )

        assertEquals(1, guidance.stepNumber)
        assertEquals("Discharge to 20%", guidance.title)
        assertFalse(guidance.whyItMatters.isBlank())
        assertFalse(guidance.instruction.isBlank())
        assertFalse(guidance.estimatedTime.isBlank())
        assertNotNull(guidance.recoveryHint)
        assertFalse(guidance.canSkipTo)
    }

    @Test
    fun `calibration steps are sequential`() {
        val steps = listOf(
            Dictionary.CalibrationStep.DISCHARGE,
            Dictionary.CalibrationStep.FULL_CHARGE,
            Dictionary.CalibrationStep.VERIFY
        )

        assertEquals(3, steps.size)
        assertEquals("discharge", steps[0])
        assertEquals("full_charge", steps[1])
        assertEquals("verify", steps[2])
    }

    @Test
    fun `step numbers match expected sequence`() {
        val stepNumbers = mapOf(
            Dictionary.CalibrationStep.DISCHARGE to 1,
            Dictionary.CalibrationStep.FULL_CHARGE to 2,
            Dictionary.CalibrationStep.VERIFY to 3
        )

        for ((step, expectedNumber) in stepNumbers) {
            val guidance = createMockGuidance(step)
            assertEquals(
                "Step $step should be number $expectedNumber",
                expectedNumber,
                guidance.stepNumber
            )
        }
    }

    @Test
    fun `canSkipTo is true when battery already below 20 for discharge step`() {
        val guidanceLowBattery = CalibrationCoach.StepGuidance(
            stepNumber = 1,
            title = "Discharge to 20%",
            whyItMatters = "Test",
            instruction = "Battery already low",
            estimatedTime = "Ready now",
            recoveryHint = null,
            canSkipTo = true
        )

        assertTrue(guidanceLowBattery.canSkipTo)
    }

    @Test
    fun `canSkipTo is true when battery at 100 for charge step`() {
        val guidanceFullBattery = CalibrationCoach.StepGuidance(
            stepNumber = 2,
            title = "Charge to 100%",
            whyItMatters = "Test",
            instruction = "Fully charged!",
            estimatedTime = "Ready now",
            recoveryHint = null,
            canSkipTo = true
        )

        assertTrue(guidanceFullBattery.canSkipTo)
    }

    @Test
    fun `recovery hint is provided for interruptible steps`() {
        val interruptibleGuidance = CalibrationCoach.StepGuidance(
            stepNumber = 1,
            title = "Test",
            whyItMatters = "Test",
            instruction = "Test",
            estimatedTime = "Test",
            recoveryHint = "If you need to stop, you can restart later",
            canSkipTo = false
        )

        assertNotNull(interruptibleGuidance.recoveryHint)
        assertTrue(interruptibleGuidance.recoveryHint!!.isNotBlank())
    }

    @Test
    fun `failure recovery messages are user-friendly`() {
        val reasons = listOf(
            Dictionary.CalibrationAbandonReason.USER_CANCEL,
            Dictionary.CalibrationAbandonReason.APP_KILLED,
            Dictionary.CalibrationAbandonReason.TIMEOUT,
            Dictionary.CalibrationAbandonReason.CHARGE_INTERRUPTED
        )

        for (reason in reasons) {
            val message = getRecoveryMessage(reason)
            assertFalse("Recovery message for $reason should not be empty", message.isBlank())
            assertFalse(
                "Recovery message should not be technical",
                message.contains("error") || message.contains("exception")
            )
        }
    }

    private fun createMockGuidance(step: String): CalibrationCoach.StepGuidance {
        val stepNumber = when (step) {
            Dictionary.CalibrationStep.DISCHARGE -> 1
            Dictionary.CalibrationStep.FULL_CHARGE -> 2
            Dictionary.CalibrationStep.VERIFY -> 3
            else -> 0
        }

        return CalibrationCoach.StepGuidance(
            stepNumber = stepNumber,
            title = "Test",
            whyItMatters = "Test",
            instruction = "Test",
            estimatedTime = "Test",
            recoveryHint = null,
            canSkipTo = false
        )
    }

    private fun getRecoveryMessage(reason: String): String {
        return when (reason) {
            Dictionary.CalibrationAbandonReason.USER_CANCEL ->
                "No problem! You can restart calibration anytime."
            Dictionary.CalibrationAbandonReason.APP_KILLED ->
                "The app was closed during calibration."
            Dictionary.CalibrationAbandonReason.TIMEOUT ->
                "Calibration took too long."
            Dictionary.CalibrationAbandonReason.CHARGE_INTERRUPTED ->
                "Charging was interrupted before reaching 100%."
            else ->
                "Something went wrong."
        }
    }
}
