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
 * With [finishOnTarget] the detector instead fires the moment the [targetClaps]th clap is
 * confirmed, without waiting for the quiet period, and so also accepts extra claps. Used to
 * silence the alarm, where speed matters more than rejecting a longer sequence.
 *
 * Pure Kotlin; no Android dependencies, so it is unit-testable on the JVM.
 *
 * @param sensitivity 0 (deaf) .. 1 (paranoid). Scales both the absolute and relative thresholds.
 * @param finishOnTarget fire as soon as [targetClaps] claps are counted.
 * Loud-and-brief is not enough to be a clap. With [verifyShape] each candidate must also *sound*
 * like one ([ClapShape]): broadband and noisy, not a low thump or a tone. And the claps of one
 * code must sound alike: a sequence whose claps differ in brightness by more than
 * [SIMILAR_RATIO] is not a deliberate code. A sound that fails the shape test is ignored, as if
 * it had not happened.
 *
 * @param verifyShape check the sound of each clap, and the similarity of a code's claps.
 * @param fastFloor let the background level follow a loud, steady sound (a sounding alarm) in
 *   both directions within about 50 ms, so a clap is judged against that sound rather than
 *   against the quiet room. Off by default: the slow floor follows loud frames over seconds, not
 *   tens of milliseconds, so claps cannot raise it.
 */
class ClapDetector(
    private val sampleRate: Int,
    private val targetClaps: Int,
    sensitivity: Float,
    private val finishOnTarget: Boolean = false,
    private val fastFloor: Boolean = false,
    private val verifyShape: Boolean = true,
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

    /** Spectral centroid of each clap counted so far in this sequence. */
    private val centroids = ArrayList<Double>()

    /** The last [RING] samples, so a confirmed clap's sound can be measured after the fact. */
    private val ring = ShortArray(RING)
    private var total = 0L
    private var onsetStartSample = 0L

    /** A clap whose decay is confirmed, waiting for enough audio after it to measure its sound. */
    private class Pending(val onsetMs: Long, val startSample: Long)
    private var pending: Pending? = null

    /** Feed [n] samples. Returns true when a matching sequence just completed. */
    fun process(samples: ShortArray, n: Int = samples.size): Boolean {
        var hit = false
        for (i in 0 until n) {
            frame[frameFill++] = samples[i]
            ring[(total % RING).toInt()] = samples[i]
            total++
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
        pending = null
        centroids.clear()
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
                pending = Pending(onsetMs, onsetStartSample)
                onsetMs = -1
            } else if (nowMs - onsetMs > MAX_CLAP_MS) {
                onsetMs = -1
                abandonSequence()
            }
        } else if (peak >= minPeak && rms > noiseRms * onsetRatio && quiet && pending == null) {
            onsetMs = nowMs
            onsetStartSample = total - frameSize
            quiet = false
            onsetRms = rms
        }

        // Count a confirmed clap once the audio needed to judge its sound has arrived.
        pending?.let { p ->
            val windowStart = maxOf(0L, p.startSample - ClapShape.LEAD)
            val ready = !verifyShape || total >= windowStart + ClapShape.WINDOW
            if (ready) {
                pending = null
                if (resolve(p, windowStart) && finishOnTarget) return true
            }
        }

        // Background tracks quickly below 3x its level and only slowly above it, so a clap (tens
        // of ms) barely moves it while a fan, a TV or traffic (seconds) is absorbed within a few
        // seconds; otherwise ambient noise above ~3.5x the floor would leave `quiet` false for
        // good and the detector deaf. With fastFloor it follows any steady level; pending onsets
        // still freeze it.
        if (onsetMs < 0) {
            if (fastFloor) noiseRms += (rms - noiseRms) * FAST_FLOOR_RATE
            else if (rms < noiseRms * 3f) noiseRms += (rms - noiseRms) * 0.05f
            else if (pending == null) noiseRms += (rms - noiseRms) * LOUD_FLOOR_RATE
        }
        if (noiseRms < 50f) noiseRms = 50f
        if (onsetMs < 0 && rms < noiseRms * 2f) quiet = true

        if (count > 0 && onsetMs < 0 && pending == null && nowMs - lastClapMs > MAX_GAP_MS) {
            val matched = count == targetClaps && similar()
            endSequence()
            return matched
        }
        return false
    }

    /**
     * Judges a confirmed clap and counts it. Returns true when, in [finishOnTarget] mode, this clap
     * completes a code of similar claps.
     */
    private fun resolve(p: Pending, windowStart: Long): Boolean {
        var centroid = 0.0
        if (verifyShape) {
            val shape = ClapShape.measure(ClapShape.WINDOW) { i -> ring[((windowStart + i) % RING).toInt()].toDouble() }
            if (!ClapShape.isClapLike(shape)) return false // Not a clap: ignored.
            centroid = shape.centroidHz
        }
        if (!registerClap(p.onsetMs)) return false
        if (verifyShape) centroids.add(centroid)
        if (finishOnTarget && count == targetClaps) {
            val ok = similar()
            endSequence()
            return ok
        }
        return false
    }

    /** True when the counted claps are alike enough to be one deliberate code. */
    private fun similar(): Boolean {
        if (centroids.size < 2) return true
        return centroids.max() / centroids.min().coerceAtLeast(1.0) <= SIMILAR_RATIO
    }

    private fun endSequence() {
        count = 0
        centroids.clear()
    }

    /** Returns false when the clap is ignored as an echo of the previous one. */
    private fun registerClap(atMs: Long): Boolean {
        if (count > 0 && atMs - lastClapMs < MIN_GAP_MS) return false
        count++
        lastClapMs = atMs
        return true
    }

    private fun abandonSequence() {
        endSequence()
    }

    companion object {
        const val FRAME_MS = 10
        const val MIN_GAP_MS = 120L
        const val MAX_GAP_MS = 1200L
        // Room reverb stretches a clap's tail; 200 ms / 40% still rejects speech and music.
        const val MAX_CLAP_MS = 200L
        const val DECAY_RATIO = 0.4f
        /** Largest brightness ratio allowed between the claps of one code. */
        const val SIMILAR_RATIO = 1.8
        private const val RING = 4096

        /** Per-frame step of the background floor while frames are louder than 3x it (~2 s to settle). */
        const val LOUD_FLOOR_RATE = 0.005f

        /** Per-frame step of the fast background floor (10 ms frames: ~50 ms to settle). */
        const val FAST_FLOOR_RATE = 0.2f
    }
}
