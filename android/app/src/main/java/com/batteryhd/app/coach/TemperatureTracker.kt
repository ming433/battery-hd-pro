package com.batteryhd.app.coach

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Temperature 24h Tracker (M2).
 *
 * Stores temperature samples for the past 24 hours to enable:
 * - Temperature history chart
 * - High-temp charging detection for AI insights
 * - Charge plan heat guidance
 */
class TemperatureTracker(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class TempSample(
        val timestampMs: Long,
        val temperatureC: Float,
        val isCharging: Boolean
    )

    fun recordSample(temperatureC: Float, isCharging: Boolean) {
        val now = System.currentTimeMillis()
        val samples = loadSamples().toMutableList()

        samples.add(TempSample(now, temperatureC, isCharging))

        val cutoff = now - TWENTY_FOUR_HOURS_MS
        val filtered = samples.filter { it.timestampMs > cutoff }

        val downsampled = if (filtered.size > MAX_SAMPLES) {
            downsample(filtered, MAX_SAMPLES)
        } else {
            filtered
        }

        saveSamples(downsampled)
    }

    fun getLast24HoursSamples(): List<TempSample> {
        val now = System.currentTimeMillis()
        val cutoff = now - TWENTY_FOUR_HOURS_MS
        return loadSamples().filter { it.timestampMs > cutoff }
    }

    fun getHighTempChargingCount(): Int {
        return getLast24HoursSamples().count { it.isCharging && it.temperatureC >= HIGH_TEMP_THRESHOLD }
    }

    fun getMaxTemp24h(): Float {
        return getLast24HoursSamples().maxOfOrNull { it.temperatureC } ?: 0f
    }

    fun getAverageTemp24h(): Float {
        val samples = getLast24HoursSamples()
        return if (samples.isNotEmpty()) {
            samples.map { it.temperatureC }.average().toFloat()
        } else {
            0f
        }
    }

    fun getChartData(): List<Pair<Long, Float>> {
        return getLast24HoursSamples().map { it.timestampMs to it.temperatureC }
    }

    private fun loadSamples(): List<TempSample> {
        val json = prefs.getString(KEY_SAMPLES, null) ?: return emptyList()

        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                TempSample(
                    timestampMs = obj.getLong("ts"),
                    temperatureC = obj.getDouble("temp").toFloat(),
                    isCharging = obj.optBoolean("charging", false)
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun saveSamples(samples: List<TempSample>) {
        val arr = JSONArray()
        samples.forEach { sample ->
            arr.put(JSONObject().apply {
                put("ts", sample.timestampMs)
                put("temp", sample.temperatureC.toDouble())
                put("charging", sample.isCharging)
            })
        }
        prefs.edit().putString(KEY_SAMPLES, arr.toString()).apply()
    }

    private fun downsample(samples: List<TempSample>, targetSize: Int): List<TempSample> {
        if (samples.size <= targetSize) return samples

        val step = samples.size.toFloat() / targetSize
        return (0 until targetSize).map { i ->
            samples[(i * step).toInt().coerceIn(0, samples.lastIndex)]
        }
    }

    companion object {
        private const val PREFS_NAME = "temp_tracker"
        private const val KEY_SAMPLES = "samples_24h"
        private const val TWENTY_FOUR_HOURS_MS = 24 * 60 * 60 * 1000L
        private const val MAX_SAMPLES = 96
        private const val HIGH_TEMP_THRESHOLD = 40f
    }
}
