package com.hereliesaz.clappiest

import kotlin.math.sqrt

/**
 * Guesses whether the phone is on its owner: in a hand, a pocket or a bag. Android has no public
 * on-body sensor for phones (Google's Smart Lock "on-body detection" is system-only), so this uses
 * the same idea: motion from the accelerometer, plus the proximity sensor for a covered phone.
 *
 *  - Each second the spread of the acceleration magnitude is measured. A phone on a table reads
 *    almost nothing; a carried one reads more even when its owner sits still.
 *  - Motion keeps the phone "on body" for [HOLD_MS] afterwards, so a pause at a traffic light does
 *    not flip it.
 *  - A covered proximity sensor (face in a pocket, a bag) counts at once, but only if the phone has
 *    also moved within [COVERED_WINDOW_MS]: a phone left face-down on a cushion is not on a body.
 *
 * It is an estimate. A phone resting in a still pocket looks like one on a table; a phone on a
 * moving train looks carried. Pure Kotlin so the logic is unit-testable.
 */
class BodyEstimator {
    private var bucketStartMs = -1L
    private var n = 0
    private var sum = 0.0
    private var sumSq = 0.0
    private var lastMotionMs = NEVER
    private var covered = false

    /** Feed one accelerometer reading (m/s²). [tMs] is any monotonic clock in milliseconds. */
    fun onAccel(x: Float, y: Float, z: Float, tMs: Long) {
        val m = sqrt((x * x + y * y + z * z).toDouble())
        if (bucketStartMs < 0) bucketStartMs = tMs
        n++; sum += m; sumSq += m * m
        if (tMs - bucketStartMs >= BUCKET_MS) {
            if (n >= MIN_SAMPLES) {
                val mean = sum / n
                val std = sqrt(((sumSq / n) - mean * mean).coerceAtLeast(0.0))
                if (std > MOTION_STD) lastMotionMs = tMs
            }
            bucketStartMs = tMs; n = 0; sum = 0.0; sumSq = 0.0
        }
    }

    /** Counts [tMs] as a moment of motion: the screen just went off, so the phone was in a hand. */
    fun noteMotion(tMs: Long) { lastMotionMs = tMs }

    /** The proximity sensor reports something close to the screen. */
    fun onProximity(near: Boolean) { covered = near }

    fun onBody(nowMs: Long): Boolean {
        if (lastMotionMs == NEVER) return false
        val sinceMotion = nowMs - lastMotionMs
        return sinceMotion <= HOLD_MS || (covered && sinceMotion <= COVERED_WINDOW_MS)
    }

    fun reset() {
        bucketStartMs = -1; n = 0; sum = 0.0; sumSq = 0.0; lastMotionMs = NEVER; covered = false
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2
        const val BUCKET_MS = 1_000L
        private const val MIN_SAMPLES = 5
        /** Spread of |a| (m/s²) above which a second counts as motion. Table noise is ~0.02; walking 2+. */
        const val MOTION_STD = 0.12
        /** How long motion keeps the phone "on body". */
        const val HOLD_MS = 45_000L
        /** A covered phone that has moved this recently is in a pocket or bag. */
        const val COVERED_WINDOW_MS = 10 * 60_000L
    }
}
