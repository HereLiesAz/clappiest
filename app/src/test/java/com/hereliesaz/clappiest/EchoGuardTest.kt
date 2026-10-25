package com.hereliesaz.clappiest

import com.hereliesaz.clappiest.EchoGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class EchoGuardTest {
    private fun guard(sounding: Float, silent: Float, soundingMs: Int = 1000, silentMs: Int = 1000): EchoGuard {
        val g = EchoGuard()
        repeat(soundingMs / 20) { g.add(sounding, 20, sounding = true) }
        repeat(silentMs / 20) { g.add(silent, 20, sounding = false) }
        return g
    }

    @Test fun unknownWithoutEnoughOfBothStates() {
        assertEquals(Verdict.UNKNOWN, EchoGuard().verdict)
        assertEquals(Verdict.UNKNOWN, guard(3000f, 200f, silentMs = 200).verdict)
        assertEquals(Verdict.UNKNOWN, guard(3000f, 200f, soundingMs = 200).verdict)
    }

    @Test fun cancelledWhenSoundingBarelyExceedsSilence() =
        assertEquals(Verdict.CANCELLED, guard(sounding = 300f, silent = 200f).verdict)

    @Test fun notCancelledWhenSoundingFarExceedsSilence() =
        assertEquals(Verdict.NOT_CANCELLED, guard(sounding = 6000f, silent = 200f).verdict)

    @Test fun aSilentRoomIsNeverQuieterThanTheMicrophoneFloor() =
        // 100 / 0 would be infinite; against the 60 floor it is 1.7, so cancelled.
        assertEquals(Verdict.CANCELLED, guard(sounding = 100f, silent = 0f).verdict)

    @Test fun boundaryIsInclusive() =
        assertEquals(Verdict.CANCELLED, guard(sounding = 500f, silent = 200f).verdict) // ratio 2.5
}
