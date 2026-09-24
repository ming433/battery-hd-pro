package com.batteryhd.app.coach

/**
 * AI Coach insight data model.
 *
 * This model represents a structured coach recommendation based on battery data.
 * The interface allows swapping between local rule-based generation and cloud LLM
 * in the future without changing the UI layer.
 */
data class CoachInsight(
    val headline: String,
    val summary: String,
    val primaryAction: PrimaryAction,
    val confidence: Confidence,
    val generatedAt: Long = System.currentTimeMillis(),
    val isOffline: Boolean = false
) {
    enum class PrimaryAction {
        SET_CHARGE_LIMIT,
        START_CALIBRATION,
        OPEN_DRAIN_DETAIL,
        ENABLE_TEMP_ALERT,
        NONE
    }

    enum class Confidence {
        HIGH,
        MEDIUM,
        LOW
    }

    companion object {
        fun noHistory(): CoachInsight = CoachInsight(
            headline = "Getting to know your battery",
            summary = "Keep charging for a few days so your coach can learn your habits and provide personalized insights.",
            primaryAction = PrimaryAction.NONE,
            confidence = Confidence.LOW
        )
    }
}
