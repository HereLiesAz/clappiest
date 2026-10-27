package com.hereliesaz.clappiest

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Whether a transient has the *sound* of a hand clap, as opposed to merely being loud and short.
 *
 * A clap is broadband and noisy: energy from about 1 kHz to 6 kHz, almost none below 400 Hz, and
 * no single pitch. Knocks, footsteps and door slams are low thumps; barks, voices, sirens and
 * alarms are tonal. All of those can be as loud and as brief as a clap, so loudness and timing
 * alone cannot tell them apart.
 *
 * The limits are deliberately loose. A clap code needs every clap to pass, so a test that keeps
 * 90% of claps would fail a 3-clap code almost half the time. On 209 real clap onsets from the
 * ESC-50 recordings this keeps 95%, and it removes almost every thump and tone. Sharp broadband
 * sounds (a can opening, a clock tick, glass) still pass: nothing about one transient separates
 * them from a clap. [ClapDetector] adds a similarity check across the claps of one code.
 *
 * Pure Kotlin so it is unit-testable on the JVM.
 */
object ClapShape {
    /** Window length: 32 ms at 16 kHz. */
    const val WINDOW = 512
    /** The window starts this many samples before the onset frame, to include the attack. */
    const val LEAD = 80

    /** Minimum spectral centroid. Thumps and rumble sit below it. */
    const val MIN_CENTROID_HZ = 900.0
    /** Maximum share of energy below [LOW_BAND_HZ]. */
    const val MAX_LOW_SHARE = 0.20
    /** Minimum flatness 1-7 kHz (1 = white noise, 0 = a pure tone). */
    const val MIN_FLATNESS = 0.02
    private const val LOW_BAND_HZ = 400.0
    private const val FLAT_FROM_HZ = 1000.0
    private const val FLAT_TO_HZ = 7000.0
    private const val SAMPLE_RATE = 16_000.0

    data class Shape(val centroidHz: Double, val lowShare: Double, val flatness: Double)

    fun isClapLike(s: Shape): Boolean =
        s.centroidHz >= MIN_CENTROID_HZ && s.lowShare <= MAX_LOW_SHARE && s.flatness >= MIN_FLATNESS

    private val hann = DoubleArray(WINDOW) { 0.5 - 0.5 * cos(2 * PI * it / (WINDOW - 1)) }
    private val cosT = DoubleArray(WINDOW / 2) { cos(2 * PI * it / WINDOW) }
    private val sinT = DoubleArray(WINDOW / 2) { -sin(2 * PI * it / WINDOW) }

    /** Measures [WINDOW] samples of [x] starting at [start]; positions past the end read as zero. */
    fun measure(x: ShortArray, start: Int): Shape =
        measure(WINDOW) { i -> val p = start + i; if (p in x.indices) x[p].toDouble() else 0.0 }

    /** Measures [n] = [WINDOW] samples supplied by [sample]. */
    fun measure(n: Int, sample: (Int) -> Double): Shape {
        require(n == WINDOW)
        val re = DoubleArray(n) { sample(it) * hann[it] }
        val im = DoubleArray(n)
        fft(re, im)
        val bins = n / 2 + 1
        val binHz = SAMPLE_RATE / n
        var energy = 0.0
        for (k in 0 until bins) energy += re[k] * re[k] + im[k] * im[k]
        if (energy < MIN_ENERGY) return SILENT // A silent window has a "flat" spectrum; it is not a clap.
        var total = 0.0; var weighted = 0.0; var low = 0.0
        var logSum = 0.0; var sum = 0.0; var count = 0
        for (k in 0 until bins) {
            val p = re[k] * re[k] + im[k] * im[k] + EPS
            val f = k * binHz
            total += p; weighted += f * p
            if (f < LOW_BAND_HZ) low += p
            if (f in FLAT_FROM_HZ..FLAT_TO_HZ) { logSum += ln(p); sum += p; count++ }
        }
        val flatness = if (count > 0) exp(logSum / count) / (sum / count) else 0.0
        return Shape(weighted / total, low / total, flatness)
    }

    private const val EPS = 1e-9
    private const val MIN_ENERGY = 1.0
    private val SILENT = Shape(0.0, 1.0, 0.0)

    /** In-place radix-2 FFT of [WINDOW] points. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var len = 2
        while (len <= n) {
            val step = n / len
            var i = 0
            while (i < n) {
                for (k in 0 until len / 2) {
                    val wr = cosT[k * step]; val wi = sinT[k * step]
                    val a = i + k; val b = a + len / 2
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr; im[b] = im[a] - xi
                    re[a] += xr; im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
    }
}
