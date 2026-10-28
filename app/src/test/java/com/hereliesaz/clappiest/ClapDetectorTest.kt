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

    /** A low thump: a door slam, a knock, a footstep. Loud and brief, but not a clap. */
    private fun thump(): ShortArray = ShortArray(rate * 100 / 1000) { i ->
        (30000 * Math.sin(2 * Math.PI * 150 * i / rate) * Math.exp(-i / (rate * 0.03)) + rnd.nextInt(-300, 300))
            .toInt().toShort()
    }

    /** A tone burst: an alarm beep, a bark, a whistle. Loud and brief, but not a clap. */
    private fun beep(): ShortArray = ShortArray(rate * 100 / 1000) { i ->
        (30000 * Math.sin(2 * Math.PI * 2000 * i / rate) * Math.exp(-i / (rate * 0.03))).toInt().toShort()
    }

    /**
     * A clap-like burst that is band-limited (about 0.7-3 kHz, centroid ~1.7 kHz against ~4 kHz for
     * white noise): it passes the shape test but sounds clearly different from [clap].
     */
    private fun dullClap(): ShortArray {
        val n = rate * 40 / 1000
        val raw = IntArray(n + 12) { rnd.nextInt(-30000, 30000) }
        fun avg(i: Int, k: Int) = (0 until k).sumOf { raw[i + it] }.toDouble() / k
        return ShortArray(n) { i ->
            ((avg(i, 4) - avg(i, 12)) * 1.5 * Math.exp(-i / (rate * 0.006))).toInt().toShort()
        }
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

    private fun run(
        target: Int, vararg parts: ShortArray,
        finishOnTarget: Boolean = false, verifyShape: Boolean = true,
    ): Int {
        val d = ClapDetector(rate, target, 0.5f, finishOnTarget, verifyShape = verifyShape)
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

    // Verifying that a loud, brief sound is actually a clap.

    @Test fun thumpsAreNotClaps() =
        assertEquals(0, run(3, silence(1000), thump(), silence(300), thump(), silence(300), thump(), silence(2000)))

    @Test fun tonesAreNotClaps() =
        assertEquals(0, run(3, silence(1000), beep(), silence(300), beep(), silence(300), beep(), silence(2000)))

    @Test fun withoutShapeCheckThumpsAreCounted() =
        // The failure this check exists to prevent.
        assertEquals(1, run(3, silence(1000), thump(), silence(300), thump(), silence(300), thump(), silence(2000), verifyShape = false))

    @Test fun aThumpAmongClapsIsIgnored() =
        assertEquals(1, run(3, silence(1000), clap(), silence(300), thump(), silence(300), clap(), silence(300), clap(), silence(2000)))

    @Test fun clapsThatSoundDifferentAreNotOneCode() =
        assertEquals(0, run(3, silence(1000), clap(), silence(300), clap(), silence(300), dullClap(), silence(2000)))

    @Test fun aDullClapAloneIsStillAClap() =
        assertEquals(1, run(3, silence(1000), dullClap(), silence(300), dullClap(), silence(300), dullClap(), silence(2000)))

    @Test fun finishOnTargetNeedsSimilarClaps() {
        assertEquals(0, run(2, silence(1000), clap(), silence(300), dullClap(), silence(300), finishOnTarget = true))
        assertEquals(1, run(2, silence(1000), clap(), silence(300), clap(), silence(300), finishOnTarget = true))
    }

    // Steady ambient noise (a fan, a TV) must not leave the detector deaf.

    private fun ambient(ms: Int, rms: Int) =
        ShortArray(rate * ms / 1000) { (rnd.nextDouble(-1.0, 1.0) * rms * 1.732).toInt().toShort() }

    private fun clapOverAmbient(rms: Int): ShortArray {
        val c = clap(); val a = ambient(40, rms)
        return ShortArray(c.size) { (c[it] + a[it]).coerceIn(-32768, 32767).toShort() }
    }

    private fun clapsOverAmbient(n: Int, rms: Int) =
        (0 until n).flatMap { listOf(clapOverAmbient(rms), ambient(300, rms)) }.toTypedArray()

    @Test fun aQuietStartThenSteadyNoiseDoesNotLeaveItDeaf() {
        // Floor settles at its minimum in the quiet, then the room gets loud and stays loud.
        for (rms in listOf(180, 300, 600, 1000)) {
            val parts = arrayOf(silence(3000), ambient(8000, rms), *clapsOverAmbient(3, rms), ambient(2000, rms))
            assertEquals("ambient rms $rms", 1, run(3, *parts))
        }
    }

    @Test fun steadyNoiseItselfIsNeverACode() =
        assertEquals(0, run(1, silence(3000), ambient(20000, 600)))

    @Test fun loudSpeechLikeSoundsDoNotRaiseTheFloorEnoughToHideClaps() {
        // A second of hum before the code must not make claps inaudible afterwards.
        assertEquals(1, run(3, silence(3000), hum(1000), silence(1500), *claps(3), silence(2000)))
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

    @Test fun theSlowFloorHearsClapsOverASteadySoundOnceItHasSettled() {
        // fastFloor settles in ~50 ms; the slow floor takes seconds but gets there.
        val parts = arrayOf(steady(6000), clapOverSteady(), steady(300), clapOverSteady(), steady(300))
        assertEquals(1, runLive(*parts, fastFloor = false))
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
