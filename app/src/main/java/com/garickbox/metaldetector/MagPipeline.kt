package com.garickbox.metaldetector

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Pipeline: raw → медиана(5) → Kalman → delta
 * После 64 сэмплов калибровки считает adaptive thresholds из sigma.
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
    var sigma = 0f
        private set
    var thresholdWarm = 5f
        private set
    var thresholdHot = 20f
        private set
    var thresholdAlarm = 50f
        private set

    // baseline-вектор для direction
    private var bx = 0f
    private var by = 0f
    private var bz = 0f

    private val samples = ArrayList<Float>(64)
    private val rawSamples = ArrayList<FloatArray>(64)

    fun resetBaseline() {
        samples.clear()
        rawSamples.clear()
        baseline = 0f
        sigma = 0f
    }

    fun captureBaseline(sample: Float, x: Float, y: Float, z: Float) {
        samples.add(sample)
        rawSamples.add(floatArrayOf(x, y, z))
        if (samples.size >= 64) {
            computeBaseline()
        }
    }

    private fun computeBaseline() {
        baseline = samples.average().toFloat()
        var sumSq = 0.0
        for (s in samples) {
            val d = (s - baseline).toDouble()
            sumSq += d * d
        }
        sigma = sqrt(sumSq / samples.size).toFloat()
        bx = rawSamples.map { it[0] }.average().toFloat()
        by = rawSamples.map { it[1] }.average().toFloat()
        bz = rawSamples.map { it[2] }.average().toFloat()

        // adaptive: warm = max(3µT, 3σ), hot = max(10µT, 6σ), alarm = max(25µT, 10σ)
        thresholdWarm  = maxOf(3f,  3f  * sigma)
        thresholdHot   = maxOf(10f, 6f  * sigma)
        thresholdAlarm = maxOf(25f, 10f * sigma)
    }

    fun isBaselineReady(): Boolean = baseline > 0f

    fun process(x: Float, y: Float, z: Float): Reading {
        val rawMag = sqrt(x * x + y * y + z * z)

        // медиана 5
        medianBuf[medianIdx] = rawMag
        medianIdx = (medianIdx + 1) % 5
        if (medianFilled < 5) medianFilled++
        val filtered = median(medianBuf, medianFilled)

        // Kalman
        val predictedP = kalmanP + kalmanQ
        val kGain = predictedP / (predictedP + kalmanR)
        kalmanX += kGain * (filtered - kalmanX)
        kalmanP = (1 - kGain) * predictedP
        val smoothed = kalmanX

        // адаптивный baseline drift (когда явно нет металла)
        if (baseline > 0f && abs(smoothed - baseline) < 2f) {
            baseline = baseline * 0.999f + smoothed * 0.001f
        }

        val delta = if (baseline > 0f) abs(smoothed - baseline) else 0f

        // direction: куда сместился вектор поля
        val direction = if (baseline > 0f) computeDirection(x, y, z) else "·"

        return Reading(smoothed, baseline, delta, x, y, z, direction)
    }

    private fun computeDirection(x: Float, y: Float, z: Float): String {
        val dx = x - bx
        val dy = y - by
        val dz = z - bz
        val ax = abs(dx); val ay = abs(dy); val az = abs(dz)
        return when {
            ax >= ay && ax >= az -> if (dx > 0) "→" else "←"
            ay >= az            -> if (dy > 0) "↑" else "↓"
            else                -> if (dz > 0) "⊕" else "⊖"
        }
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
    val z: Float,
    val direction: String = "·"
)
