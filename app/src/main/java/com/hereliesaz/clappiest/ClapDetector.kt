package com.hereliesaz.clappiest

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Detects a sequence of exactly [targetClaps] hand claps in a 16-bit PCM mono stream.
 *
 * A clap is an impulse: a sudden jump well above the background level that decays
 * almost as quickly as it arrived. Speech, music and slammed-door rumble rise or
 * linger too long and are rejected.
 *
 * Claps belong to one sequence while each gap is between [MIN_GAP_MS] and [MAX_GAP_MS].
 * The sequence is judged once [MAX_GAP_MS] of silence follows the last clap, so four
 * claps never satisfy a target of three.
 *
 * Pure Kotlin; no Android dependencies, so it is unit-testable on the JVM.
 *
 * @param sensitivity 0 (deaf) .. 1 (paranoid). Scales both the absolute and relative thresholds.
 */
class ClapDetector(
    private val sampleRate: Int,
    private val targetClaps: Int,
    sensitivity: Float,
) {
    private val frameSize = sampleRate * FRAME_MS / 1000
    private val frame = ShortArray(frameSize)
    private var frameFill = 0
    private var nowMs = 0L

    private val s = sensitivity.coerceIn(0f, 1f)
    /** Absolute peak a clap must reach, as a fraction of full scale. */
    private val minPeak = (0.30f - 0.25f * s) * Short.MAX_VALUE
    /** How far above the background RMS a clap's RMS must rise. */
    private val onsetRatio = 6f - 4f * s

    private var noiseRms = 300f
    /** An onset needs quiet before it, so a long sound cannot re-trigger halfway through. */
    private var quiet = true

    // Pending onset awaiting proof that it decays like a clap.
    private var onsetMs = -1L
    private var onsetRms = 0f

    private var lastClapMs = -1L
    private var count = 0

    /** Feed [n] samples. Returns true when a matching sequence just completed. */
    fun process(samples: ShortArray, n: Int = samples.size): Boolean {
        var hit = false
        for (i in 0 until n) {
            frame[frameFill++] = samples[i]
            if (frameFill == frameSize) {
                if (onFrame()) hit = true
                frameFill = 0
                nowMs += FRAME_MS
            }
        }
        return hit
    }

    fun reset() {
        frameFill = 0
        onsetMs = -1
        quiet = true
        lastClapMs = -1
        count = 0
    }

    private fun onFrame(): Boolean {
        var peak = 0
        var sumSq = 0.0
        for (v in frame) {
            val a = abs(v.toInt())
            if (a > peak) peak = a
            sumSq += a.toDouble() * a
        }
        val rms = sqrt(sumSq / frameSize).toFloat()

        if (onsetMs >= 0) {
            // Clap confirmed once energy collapses; rejected if it lingers.
            if (rms < onsetRms * DECAY_RATIO) {
                registerClap(onsetMs)
                onsetMs = -1
            } else if (nowMs - onsetMs > MAX_CLAP_MS) {
                onsetMs = -1
                abandonSequence()
            }
        } else if (peak >= minPeak && rms > noiseRms * onsetRatio && quiet) {
            onsetMs = nowMs
            quiet = false
            onsetRms = rms
        }

        // Background tracks slowly and ignores loud frames so claps don't raise it.
        if (onsetMs < 0 && rms < noiseRms * 3f) noiseRms += (rms - noiseRms) * 0.05f
        if (noiseRms < 50f) noiseRms = 50f
        if (onsetMs < 0 && rms < noiseRms * 2f) quiet = true

        if (count > 0 && onsetMs < 0 && nowMs - lastClapMs > MAX_GAP_MS) {
            val matched = count == targetClaps
            count = 0
            return matched
        }
        return false
    }

    private fun registerClap(atMs: Long) {
        if (count > 0 && atMs - lastClapMs < MIN_GAP_MS) return
        count++
        lastClapMs = atMs
    }

    private fun abandonSequence() {
        count = 0
    }

    companion object {
        const val FRAME_MS = 10
        const val MIN_GAP_MS = 120L
        const val MAX_GAP_MS = 1200L
        // Room reverb stretches a clap's tail; 200 ms / 40% still rejects speech and music.
        const val MAX_CLAP_MS = 200L
        const val DECAY_RATIO = 0.4f
    }
}
