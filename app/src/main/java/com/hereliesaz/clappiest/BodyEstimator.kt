package com.hereliesaz.clappiest

import kotlin.math.sqrt

/**
 * Guesses whether the phone is on its owner: in a hand, a pocket or a bag. Android has no public
 * on-body sensor for phones. Google's own "Extend Unlock" on-body detection (Play services) works
 * from the accelerometer too, and no app can query it, so this does the same job itself:
 *
 *  - Each second the spread of the acceleration magnitude is measured. A phone on a table reads
 *    almost nothing; a carried one reads more even when its owner sits still.
 *  - Real motion (walking, handling) keeps the phone "on body" for [HOLD_MS] afterwards, so a
 *    pause at a traffic light does not flip it.
 *  - Pocket: the proximity sensor is covered **and** the phone keeps showing the small tremors of a
 *    body ([MICRO_STD]: breathing, a shifting leg) within the last [POCKET_HOLD_MS]. A phone that
 *    fell between couch cushions is covered too but then lies dead still, so it stops counting as
 *    pocketed within [POCKET_HOLD_MS] of landing there.
 *
 * It is an estimate and the thresholds are untuned. A phone in a pocket of someone sitting
 * perfectly still can read as a table; a cushion someone is sitting on can read as a pocket.
 * Pure Kotlin so the logic is unit-testable.
 */
class BodyEstimator {
    private var bucketStartMs = -1L
    private var n = 0
    private var sum = 0.0
    private var sumSq = 0.0
    private var lastMotionMs = NEVER
    private var lastMicroMs = NEVER
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
                if (std > MICRO_STD) lastMicroMs = tMs
            }
            bucketStartMs = tMs; n = 0; sum = 0.0; sumSq = 0.0
        }
    }

    /** Counts [tMs] as a moment of motion: the screen just went off, so the phone was in a hand. */
    fun noteMotion(tMs: Long) { lastMotionMs = tMs; lastMicroMs = tMs }

    /** The proximity sensor reports something close to the screen. */
    fun onProximity(near: Boolean) { covered = near }

    fun onBody(nowMs: Long): Boolean {
        return nowMs - lastMotionMs <= HOLD_MS || (covered && nowMs - lastMicroMs <= POCKET_HOLD_MS)
    }

    fun reset() {
        bucketStartMs = -1; n = 0; sum = 0.0; sumSq = 0.0; lastMotionMs = NEVER; lastMicroMs = NEVER; covered = false
    }

    companion object {
        private const val NEVER = Long.MIN_VALUE / 2
        const val BUCKET_MS = 1_000L
        private const val MIN_SAMPLES = 5
        /** Spread of |a| (m/s²) above which a second counts as motion. Table noise is ~0.02; walking 2+. */
        const val MOTION_STD = 0.12
        /** How long motion keeps the phone "on body". */
        const val HOLD_MS = 45_000L
        /** Spread of |a| above which a second shows a body's tremor. Table noise is ~0.01-0.02. */
        const val MICRO_STD = 0.05
        /** A covered phone that showed a body's tremor this recently is in a pocket or bag. */
        const val POCKET_HOLD_MS = 90_000L
    }
}
