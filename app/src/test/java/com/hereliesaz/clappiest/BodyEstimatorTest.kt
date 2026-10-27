package com.hereliesaz.clappiest

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.random.Random

class BodyEstimatorTest {
    private val rnd = Random(11)

    /** Feeds [seconds] of 50 Hz accelerometer data, [jitter] m/s² of noise about gravity. */
    private fun BodyEstimator.feed(fromMs: Long, seconds: Int, jitter: Double): Long {
        var t = fromMs
        repeat(seconds * 50) {
            val z = 9.81 + rnd.nextDouble(-jitter, jitter)
            onAccel(0f, 0f, z.toFloat(), t)
            t += 20
        }
        return t
    }

    @Test fun aPhoneOnATableIsNotOnABody() {
        val e = BodyEstimator()
        val t = e.feed(0, 120, 0.02)
        assertEquals(false, e.onBody(t))
    }

    @Test fun aPhoneBeingWalkedWithIs() {
        val e = BodyEstimator()
        val t = e.feed(0, 10, 3.0)
        assertEquals(true, e.onBody(t))
    }

    @Test fun motionKeepsItOnBodyForAWhileThenReleases() {
        val e = BodyEstimator()
        var t = e.feed(0, 5, 3.0)
        t = e.feed(t, 30, 0.02)
        assertEquals(true, e.onBody(t))        // paused at a light
        t = e.feed(t, 60, 0.02)
        assertEquals(false, e.onBody(t))       // put down
    }

    @Test fun aPocketedPhoneWithABodysTremorStaysOnBody() {
        val e = BodyEstimator()
        var t = e.feed(0, 5, 3.0)
        e.onProximity(true)
        t = e.feed(t, 300, 0.15)               // sitting, phone in pocket: small steady tremor
        assertEquals(true, e.onBody(t))
    }

    @Test fun aPhoneBetweenCouchCushionsIsNotOnBody() {
        val e = BodyEstimator()
        var t = e.feed(0, 5, 3.0)              // dropped
        e.onProximity(true)                    // swallowed by the cushions
        t = e.feed(t, 30, 0.02)
        assertEquals(true, e.onBody(t))        // still inside the hold after the drop
        t = e.feed(t, 120, 0.02)               // lies dead still
        assertEquals(false, e.onBody(t))
    }

    @Test fun coveredButNeverMovedIsNotOnBody() {
        val e = BodyEstimator()
        e.onProximity(true)
        val t = e.feed(0, 30, 0.02)
        assertEquals(false, e.onBody(t))
    }

    @Test fun uncoveringEndsThePocketCase() {
        val e = BodyEstimator()
        var t = e.feed(0, 5, 3.0)
        e.onProximity(true)
        t = e.feed(t, 120, 0.15)
        e.onProximity(false)
        assertEquals(false, e.onBody(t))
    }

    @Test fun resetForgetsEverything() {
        val e = BodyEstimator()
        val t = e.feed(0, 5, 3.0)
        e.reset()
        assertEquals(false, e.onBody(t))
    }
}
