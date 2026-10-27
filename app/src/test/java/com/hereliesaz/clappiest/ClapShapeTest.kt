package com.hereliesaz.clappiest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.random.Random

class ClapShapeTest {
    private val rnd = Random(11)
    private val n = ClapShape.WINDOW
    private val rate = 16_000

    private fun noiseBurst() = ShortArray(n) { i -> (rnd.nextInt(-30000, 30000) * exp(-i / (rate * 0.006))).toInt().toShort() }
    private fun thump() = ShortArray(n) { i -> (30000 * sin(2 * PI * 150 * i / rate) * exp(-i / (rate * 0.03))).toInt().toShort() }
    private fun tone() = ShortArray(n) { i -> (30000 * sin(2 * PI * 2000 * i / rate) * exp(-i / (rate * 0.03))).toInt().toShort() }

    @Test fun aNoiseBurstIsClapLike() {
        val s = ClapShape.measure(noiseBurst(), 0)
        assertTrue("$s", ClapShape.isClapLike(s))
        assertTrue(s.centroidHz > 2500)
    }

    @Test fun aLowThumpIsNot() {
        val s = ClapShape.measure(thump(), 0)
        assertFalse("$s", ClapShape.isClapLike(s))
        assertTrue(s.lowShare > 0.9)
    }

    @Test fun aToneIsNot() {
        val s = ClapShape.measure(tone(), 0)
        assertFalse("$s", ClapShape.isClapLike(s))
        assertTrue(s.flatness < 0.01)
    }

    @Test fun silenceIsNeverAClap() =
        assertFalse(ClapShape.isClapLike(ClapShape.measure(ShortArray(n), 0)))

    @Test fun readsPastTheEndAsSilence() {
        val a = ClapShape.measure(noiseBurst(), 0)
        val b = ClapShape.measure(ShortArray(100) { 1000 }, 50) // mostly past the end
        assertTrue(a.centroidHz > 0 && b.centroidHz >= 0)
    }

    /** The FFT must agree with a plain DFT of the same windowed samples. */
    @Test fun fftMatchesANaiveDft() {
        val x = ShortArray(n) { rnd.nextInt(-20000, 20000).toShort() }
        val fast = ClapShape.measure(x, 0)

        val w = DoubleArray(n) { x[it] * (0.5 - 0.5 * cos(2 * PI * it / (n - 1))) }
        val bins = n / 2 + 1
        val p = DoubleArray(bins) { k ->
            var re = 0.0; var im = 0.0
            for (i in 0 until n) { re += w[i] * cos(2 * PI * k * i / n); im -= w[i] * sin(2 * PI * k * i / n) }
            re * re + im * im + 1e-9
        }
        val hz = rate.toDouble() / n
        val total = p.sum()
        val centroid = p.indices.sumOf { it * hz * p[it] } / total
        val low = p.indices.filter { it * hz < 400 }.sumOf { p[it] } / total
        val band = p.indices.filter { it * hz in 1000.0..7000.0 }
        val flat = exp(band.sumOf { ln(p[it]) } / band.size) / (band.sumOf { p[it] } / band.size)

        assertEquals(centroid, fast.centroidHz, centroid * 1e-6)
        assertEquals(low, fast.lowShare, 1e-9)
        assertEquals(flat, fast.flatness, flat * 1e-6)
    }
}
