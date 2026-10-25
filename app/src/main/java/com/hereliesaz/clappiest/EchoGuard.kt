package com.hereliesaz.clappiest

import kotlin.math.sqrt

/**
 * Decides whether echo cancellation is hiding the sounding alarm from the microphone.
 *
 * Feed it the microphone level while the alarm is audible and while it is silent (a replay gap
 * or a muted window). If the audible level is barely above the silent level, the alarm is being
 * cancelled and claps can be heard over it. If it is far above, cancellation is absent or
 * ineffective and the caller must fall back to listening only while the alarm is silent.
 *
 * Pure Kotlin so it is unit-testable on the JVM.
 *
 * @param minMs audio required in each state before a verdict is given.
 * @param maxRatio audible-to-silent RMS ratio at or below which the alarm counts as cancelled.
 */
class EchoGuard(
    private val minMs: Int = 500,
    private val maxRatio: Float = 2.5f,
) {
    enum class Verdict { UNKNOWN, CANCELLED, NOT_CANCELLED }

    private var soundingSq = 0.0
    private var soundingMs = 0
    private var silentSq = 0.0
    private var silentMs = 0

    /** Add one block: its RMS, its length, and whether the alarm was audible during it. */
    fun add(rms: Float, ms: Int, sounding: Boolean) {
        val sq = rms.toDouble() * rms * ms
        if (sounding) { soundingSq += sq; soundingMs += ms } else { silentSq += sq; silentMs += ms }
    }

    val verdict: Verdict
        get() {
            if (soundingMs < minMs || silentMs < minMs) return Verdict.UNKNOWN
            val sounding = sqrt(soundingSq / soundingMs).toFloat()
            // A silent room is never quieter than the microphone's own noise.
            val silent = sqrt(silentSq / silentMs).toFloat().coerceAtLeast(NOISE_FLOOR)
            return if (sounding / silent <= maxRatio) Verdict.CANCELLED else Verdict.NOT_CANCELLED
        }

    companion object {
        const val NOISE_FLOOR = 60f
    }
}
