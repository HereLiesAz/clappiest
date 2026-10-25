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

    /** Distant clap in a live room: modest peak, slow reverberant tail. */
    private fun roomClap(): ShortArray = ShortArray(rate * 150 / 1000) { i ->
        val env = Math.exp(-i / (rate * 0.03)).toFloat()
        (rnd.nextInt(-9000, 9000) * env).toInt().toShort()
    }

    /** Loud sustained tone: speech/music stand-in. */
    private fun hum(ms: Int) = ShortArray(rate * ms / 1000) { i ->
        (20000 * Math.sin(2 * Math.PI * 220 * i / rate)).toInt().toShort()
    }

    private fun run(target: Int, vararg parts: ShortArray, finishOnTarget: Boolean = false): Int {
        val d = ClapDetector(rate, target, 0.5f, finishOnTarget)
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

    @Test fun distantReverberantClapsTrigger() {
        val parts = (0 until 3).flatMap { listOf(roomClap(), silence(250)) }.toTypedArray()
        assertEquals(1, run(3, silence(1000), *parts, silence(2000)))
    }

    // fastFloor: hearing claps over a loud, steady sound such as a cancelled alarm's residue.

    /** A steady tone standing in for the alarm's residue, rms about 2100. */
    private fun steady(ms: Int) = ShortArray(rate * ms / 1000) { i ->
        (3000 * Math.sin(2 * Math.PI * 440 * i / rate)).toInt().toShort()
    }

    /** A clap landing on top of the steady tone. */
    private fun clapOverSteady(): ShortArray {
        val c = clap(); val t = steady(c.size * 1000 / rate)
        return ShortArray(c.size) { (c[it] + t[it]).coerceIn(-32768, 32767).toShort() }
    }

    private fun runLive(vararg parts: ShortArray, fastFloor: Boolean): Int {
        val d = ClapDetector(rate, 2, 0.5f, finishOnTarget = true, fastFloor = fastFloor)
        var hits = 0
        for (p in parts) if (d.process(p)) hits++
        return hits
    }

    @Test fun fastFloorHearsTwoClapsOverASteadySound() {
        val parts = arrayOf(steady(1000), clapOverSteady(), steady(300), clapOverSteady(), steady(300))
        assertEquals(1, runLive(*parts, fastFloor = true))
    }

    @Test fun theSlowFloorCannotHearClapsOverASteadySound() {
        // Why fastFloor exists: the slow floor never adapts to a loud steady sound.
        val parts = arrayOf(steady(1000), clapOverSteady(), steady(300), clapOverSteady(), steady(300))
        assertEquals(0, runLive(*parts, fastFloor = false))
    }

    @Test fun fastFloorIgnoresTheSteadySoundItself() =
        assertEquals(0, runLive(silence(1000), steady(1500), silence(1000), steady(1500), fastFloor = true))

    @Test fun fastFloorIgnoresASingleClap() =
        assertEquals(0, runLive(steady(1000), clapOverSteady(), steady(2500), fastFloor = true))

    // finishOnTarget: used to silence the alarm.

    @Test fun finishOnTargetFiresWithoutWaitingForQuiet() {
        // Ends 300 ms after the second clap, far short of the 1.2 s the default mode waits.
        val parts = arrayOf(silence(1000), clap(), silence(300), clap(), silence(300))
        assertEquals(1, run(2, *parts, finishOnTarget = true))
        assertEquals(0, run(2, *parts))
    }

    @Test fun finishOnTargetIgnoresASingleClap() =
        assertEquals(0, run(2, silence(1000), clap(), silence(2000), finishOnTarget = true))

    @Test fun finishOnTargetIgnoresSeparatedClaps() =
        assertEquals(0, run(2, silence(1000), clap(), silence(2500), clap(), silence(2500), finishOnTarget = true))

    @Test fun finishOnTargetIgnoresSustainedSound() =
        assertEquals(0, run(2, silence(1000), hum(500), silence(300), hum(500), silence(2000), finishOnTarget = true))

    @Test fun finishOnTargetFiresAgainAfterAFreshPair() =
        assertEquals(2, run(2, silence(1000), *claps(2), silence(1500), *claps(2), silence(500), finishOnTarget = true))

    @Test fun triggersRepeatedly() =
        assertEquals(2, run(2, silence(1000), *claps(2), silence(2000), *claps(2), silence(2000)))
}
