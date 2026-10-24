package com.hereliesaz.clappiest

import kotlin.math.sqrt

/**
 * Detects a recorded custom sound (such as a whistle) using normalized cross-correlation.
 */
class CustomSoundDetector(
    referenceSamples: ShortArray,
    sensitivity: Float,
) {
    private val threshold = 0.55f + (sensitivity * 0.3f).coerceIn(0f, 0.35f)
    private val windowSize = referenceSamples.size
    private val streamBuffer = FloatArray(windowSize)
    private var bufferHead = 0
    private var filled = false

    private val refFloat = FloatArray(windowSize) { referenceSamples[it].toFloat() }
    private val refMean = refFloat.average().toFloat()
    private val refNorm = sqrt(refFloat.sumOf { v ->
        val diff = (v - refMean).toDouble()
        diff * diff
    }).toFloat().coerceAtLeast(1e-5f)

    fun process(samples: ShortArray, n: Int = samples.size): Boolean {
        if (windowSize == 0) return false
        for (i in 0 until n) {
            streamBuffer[bufferHead] = samples[i].toFloat()
            bufferHead = (bufferHead + 1) % windowSize
            if (bufferHead == 0) filled = true

            if (filled && i % 160 == 0) { // check every 10ms
                if (checkCorrelation()) return true
            }
        }
        return false
    }

    private fun checkCorrelation(): Boolean {
        val x = FloatArray(windowSize)
        for (i in 0 until windowSize) {
            x[i] = streamBuffer[(bufferHead + i) % windowSize]
        }
        val xMean = x.average().toFloat()
        val xNorm = sqrt(x.sumOf { v ->
            val diff = (v - xMean).toDouble()
            diff * diff
        }).toFloat().coerceAtLeast(1e-5f)

        var dot = 0.0f
        for (i in 0 until windowSize) {
            dot += (x[i] - xMean) * (refFloat[i] - refMean)
        }

        val correlation = dot / (xNorm * refNorm)
        return correlation >= threshold
    }
}
