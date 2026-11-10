package com.garickbox.metaldetector

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pipeline обработки магнитометра:
 *   raw → медиана(5) → Kalman → delta от baseline
 * Медиана убирает выбросы (рывки телефона), Kalman сглаживает шум сенсора.
 */
class MagPipeline {
    private val medianBuf = FloatArray(5)
    private var medianIdx = 0
    private var medianFilled = 0

    private var kalmanX = 0f
    private var kalmanP = 1f
    private val kalmanQ = 0.01f
    private val kalmanR = 0.5f

    var baseline = 0f
        private set
    private val baselineSamples = ArrayList<Float>(64)

    fun resetBaseline() {
        baselineSamples.clear()
    }

    fun captureBaseline(sample: Float) {
        baselineSamples.add(sample)
        if (baselineSamples.size >= 64) {
            baseline = baselineSamples.average().toFloat()
        }
    }

    fun isBaselineReady(): Boolean = baselineSamples.size >= 64 || baseline > 0f

    fun process(x: Float, y: Float, z: Float): Reading {
        val rawMag = sqrt(x * x + y * y + z * z)

        // Медиана 5
        medianBuf[medianIdx] = rawMag
        medianIdx = (medianIdx + 1) % 5
        if (medianFilled < 5) medianFilled++
        val filtered = median(medianBuf, medianFilled)

        // Kalman
        val predictedP = kalmanP + kalmanQ
        val kGain = predictedP / (predictedP + kalmanR)
        kalmanX += kGain * (filtered - kalmanX)
        kalmanP = (1 - kGain) * predictedP

        // Адаптивный baseline: если явно нет металла (Δ<2) — подтягиваем
        val smoothed = kalmanX
        if (baseline > 0f && abs(smoothed - baseline) < 2f) {
            baseline = baseline * 0.999f + smoothed * 0.001f
        }

        val delta = if (baseline > 0f) abs(smoothed - baseline) else 0f
        return Reading(smoothed, baseline, delta, x, y, z)
    }

    private fun median(arr: FloatArray, n: Int): Float {
        if (n == 0) return 0f
        val copy = arr.copyOf(n)
        copy.sort()
        return copy[n / 2]
    }
}

data class Reading(
    val smoothed: Float,
    val baseline: Float,
    val delta: Float,
    val x: Float,
    val y: Float,
    val z: Float
)
