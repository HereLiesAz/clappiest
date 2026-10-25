package com.hereliesaz.clappiest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VersionsTest {
    @Test fun parsesLeadingDottedIntegers() {
        assertEquals(listOf(0, 1, 8), Versions.parse("0.1.8"))
        assertEquals(listOf(0, 1, 8), Versions.parse("v0.1.8"))
        assertEquals(listOf(0, 1, 8, 1), Versions.parse(" 0.1.8.1 "))
        assertEquals(listOf(0, 2, 0), Versions.parse("0.2.0-beta"))
        assertEquals(listOf(10, 0, 0), Versions.parse("V10.0.0"))
    }

    @Test fun rejectsNonVersions() {
        assertNull(Versions.parse(""))
        assertNull(Versions.parse("latest"))
        assertNull(Versions.parse("nightly-2026"))
    }

    @Test fun newerWhenAnyOfTheFirstThreeIsHigher() {
        assertTrue(Versions.isNewer("0.1.9", "0.1.8"))
        assertTrue(Versions.isNewer("0.2.0", "0.1.9"))
        assertTrue(Versions.isNewer("1.0.0", "0.9.9"))
        assertTrue(Versions.isNewer("0.1.10", "0.1.9")) // numeric, not lexical
    }

    @Test fun notNewerWhenEqualOrOlder() {
        assertFalse(Versions.isNewer("0.1.8", "0.1.8"))
        assertFalse(Versions.isNewer("0.1.7", "0.1.8"))
        assertFalse(Versions.isNewer("0.1.9", "0.2.0"))
    }

    @Test fun aFourthPartIsNotAnUpdate() {
        assertFalse(Versions.isNewer("0.1.8", "0.1.8.1"))
        assertFalse(Versions.isNewer("0.1.8.5", "0.1.8.1"))
        assertTrue(Versions.isNewer("0.1.9", "0.1.8.1"))
    }

    @Test fun missingPartsCountAsZero() {
        assertFalse(Versions.isNewer("0.1", "0.1.0"))
        assertTrue(Versions.isNewer("0.2", "0.1.9"))
    }

    @Test fun unparsableIsNeverNewer() {
        assertFalse(Versions.isNewer("latest", "0.1.8"))
        assertFalse(Versions.isNewer("0.1.9", ""))
    }

    private val now = 1_000_000L

    @Test fun promptsForANewVersion() =
        assertTrue(Versions.shouldPrompt("0.1.9", "0.1.8", null, 0, now))

    @Test fun doesNotPromptWithoutAKnownVersionOrWhenCurrent() {
        assertFalse(Versions.shouldPrompt(null, "0.1.8", null, 0, now))
        assertFalse(Versions.shouldPrompt("0.1.8", "0.1.8", null, 0, now))
    }

    @Test fun laterSnoozesThatVersionOnly() {
        assertFalse(Versions.shouldPrompt("0.1.9", "0.1.8", "0.1.9", now + 1, now))
        assertTrue(Versions.shouldPrompt("0.2.0", "0.1.8", "0.1.9", now + 1, now)) // newer version asks again
    }

    @Test fun theSnoozeExpires() =
        assertTrue(Versions.shouldPrompt("0.1.9", "0.1.8", "0.1.9", now - 1, now))
}
