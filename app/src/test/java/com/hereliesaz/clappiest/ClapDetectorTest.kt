package com.hereliesaz.clappiest

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class ClapDetectorTest {
    private val rate = 16_000
    private val rnd = Random(7)

    private fun silence(ms: Int) = ShortArray(rate * ms / 1000) { (rnd.nextInt(-200, 200)).toShort() }

    /** Sharp burst of noise with a fast exponential decay: a clap. */
    private fun clap(): ShortArray = ShortArray(rate * 40 / 1000) { i ->
        val env = Math.exp(-i / (rate * 0.006)).toFloat()
        (rnd.nextInt(-30000, 30000) * env).toInt().toShort()
    }

    /** Loud sustained tone: speech/music stand-in. */
    private fun hum(ms: Int) = ShortArray(rate * ms / 1000) { i ->
        (20000 * Math.sin(2 * Math.PI * 220 * i / rate)).toInt().toShort()
    }

    private fun run(target: Int, vararg parts: ShortArray): Int {
        val d = ClapDetector(rate, target, 0.5f)
        var hits = 0
        for (p in parts) if (d.process(p)) hits++
        return hits
    }

    private fun claps(n: Int, gapMs: Int = 300) =
        (0 until n).flatMap { listOf(clap(), silence(gapMs)) }.toTypedArray()

    @Test fun exactCountTriggers() =
        assertEquals(1, run(3, silence(1000), *claps(3), silence(2000)))

    @Test fun tooFewDoesNot() =
        assertEquals(0, run(3, silence(1000), *claps(2), silence(2000)))

    @Test fun tooManyDoesNot() =
        assertEquals(0, run(3, silence(1000), *claps(4), silence(2000)))

    @Test fun separatedGroupsDoNotCombine() =
        assertEquals(0, run(4, silence(1000), *claps(2), silence(2000), *claps(2), silence(2000)))

    @Test fun sustainedSoundIgnored() =
        assertEquals(0, run(1, silence(1000), hum(500), silence(2000)))

    @Test fun triggersRepeatedly() =
        assertEquals(2, run(2, silence(1000), *claps(2), silence(2000), *claps(2), silence(2000)))
}
