package com.hereliesaz.clappiest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SnoozeTest {
    private val now = 1_000_000L

    @Test fun notSnoozedByDefault() =
        assertFalse(Snooze.isSnoozed("7", null, 0, now))

    @Test fun laterSnoozesThatUpdateOnly() {
        assertTrue(Snooze.isSnoozed("7", "7", now + 1, now))
        assertFalse(Snooze.isSnoozed("8", "7", now + 1, now)) // a newer update asks again
    }

    @Test fun theSnoozeExpires() =
        assertFalse(Snooze.isSnoozed("7", "7", now - 1, now))

    @Test fun theSnoozeEndsExactlyAtItsDeadline() =
        assertFalse(Snooze.isSnoozed("7", "7", now, now))
}
