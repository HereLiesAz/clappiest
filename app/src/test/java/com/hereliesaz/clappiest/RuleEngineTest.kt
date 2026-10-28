package com.hereliesaz.clappiest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {
    private val mon = 0
    private val wed = 2
    private val sat = 5
    private fun at(day: Int, h: Int, m: Int = 0) = Conditions(day = day, minuteOfDay = h * 60 + m)
    private fun eval(r: Rules, c: Conditions) = RuleEngine.evaluate(r, c)
    private fun paused(d: Decision) = d is Decision.Pause

    @Test fun noRulesAlwaysListens() {
        assertEquals(Decision.Listen, eval(Rules(), at(wed, 12)))
        assertTrue(!Rules().any)
    }

    // Schedule

    private val work = Rules(schedule = Presence.NOT_WHEN, days = Rules.WEEKDAYS, startMin = 9 * 60, endMin = 17 * 60)

    @Test fun notDuringWorkHours() {
        assertTrue(paused(eval(work, at(wed, 9))))
        assertTrue(paused(eval(work, at(wed, 16, 59))))
        assertEquals(Decision.Listen, eval(work, at(wed, 17)))
        assertEquals(Decision.Listen, eval(work, at(wed, 8, 59)))
    }

    @Test fun workHoursDoNotApplyAtTheWeekend() =
        assertEquals(Decision.Listen, eval(work, at(sat, 12)))

    @Test fun onlyDuringTheWindow() {
        val r = work.copy(schedule = Presence.ONLY_WHEN)
        assertEquals(Decision.Listen, eval(r, at(wed, 12)))
        assertTrue(paused(eval(r, at(wed, 20))))
        assertTrue(paused(eval(r, at(sat, 12))))
    }

    @Test fun anOvernightWindowRunsPastMidnightOnTheDayItStarted() {
        // Friday 22:00 to 06:00: Saturday 03:00 is inside, Friday 03:00 is not.
        val r = Rules(schedule = Presence.NOT_WHEN, days = 1 shl 4, startMin = 22 * 60, endMin = 6 * 60)
        assertTrue(paused(eval(r, at(4, 23))))
        assertTrue(paused(eval(r, at(5, 3))))
        assertEquals(Decision.Listen, eval(r, at(4, 3)))
        assertEquals(Decision.Listen, eval(r, at(5, 7)))
    }

    @Test fun sundayNightWindowWrapsToMonday() {
        val r = Rules(schedule = Presence.NOT_WHEN, days = 1 shl 6, startMin = 22 * 60, endMin = 6 * 60)
        assertTrue(paused(eval(r, at(0, 2))))
    }

    @Test fun equalStartAndEndMeansTheWholeDay() {
        val r = Rules(schedule = Presence.NOT_WHEN, days = 1 shl 5, startMin = 0, endMin = 0)
        assertTrue(paused(eval(r, at(sat, 15))))
        assertEquals(Decision.Listen, eval(r, at(wed, 15)))
    }

    @Test fun noDaysSelectedMeansTheScheduleNeverApplies() =
        assertEquals(Decision.Listen, eval(work.copy(days = 0), at(wed, 12)))

    @Test fun nextEdgeIsTheNearerOfOpenAndClose() {
        assertEquals(60, RuleEngine.minutesToNextEdge(work, 8 * 60))
        assertEquals(30, RuleEngine.minutesToNextEdge(work, 16 * 60 + 30))
        // After close the next edge is tomorrow's open: 15 h 30 min after 17:30.
        assertEquals(15 * 60 + 30, RuleEngine.minutesToNextEdge(work, 17 * 60 + 30))
        // Exactly on an edge, the next one is the other edge, not zero minutes away.
        assertEquals(8 * 60, RuleEngine.minutesToNextEdge(work, 9 * 60))
        assertNull(RuleEngine.minutesToNextEdge(Rules(), 600))
    }

    // Charging, battery, headphones, body

    @Test fun chargingRules() {
        val c = at(wed, 12)
        assertTrue(paused(eval(Rules(charging = Presence.NOT_WHEN), c.copy(charging = true))))
        assertEquals(Decision.Listen, eval(Rules(charging = Presence.NOT_WHEN), c.copy(charging = false)))
        assertTrue(paused(eval(Rules(charging = Presence.ONLY_WHEN), c.copy(charging = false))))
    }

    @Test fun batteryFloor() {
        val r = Rules(batteryMin = 20)
        assertTrue(paused(eval(r, at(wed, 12).copy(batteryPct = 19))))
        assertEquals(Decision.Listen, eval(r, at(wed, 12).copy(batteryPct = 20)))
    }

    @Test fun headphonesRule() {
        val r = Rules(headphones = Presence.NOT_WHEN)
        assertTrue(paused(eval(r, at(wed, 12).copy(headphones = true))))
        assertEquals(Decision.Listen, eval(r, at(wed, 12).copy(headphones = false)))
    }

    @Test fun pauseWhileOnBody() {
        val r = Rules(onBody = Presence.NOT_WHEN)
        assertEquals(Decision.Pause("on you"), eval(r, at(wed, 12).copy(onBody = true)))
        assertEquals(Decision.Listen, eval(r, at(wed, 12).copy(onBody = false)))
    }

    @Test fun unknownConditionsNeverPause() {
        val r = Rules(
            charging = Presence.ONLY_WHEN, batteryMin = 50, headphones = Presence.ONLY_WHEN, onBody = Presence.ONLY_WHEN,
            places = Presence.ONLY_WHEN, placeList = listOf(Place("W", 0.0, 0.0, 100)),
            wifi = Presence.ONLY_WHEN, wifiList = listOf("Work"),
        )
        assertEquals(Decision.Listen, eval(r, at(wed, 12)))
    }

    @Test fun anyRuleThatFailsPauses() {
        val r = Rules(charging = Presence.NOT_WHEN, onBody = Presence.NOT_WHEN)
        assertTrue(paused(eval(r, at(wed, 12).copy(charging = false, onBody = true))))
        assertTrue(paused(eval(r, at(wed, 12).copy(charging = true, onBody = false))))
        assertEquals(Decision.Listen, eval(r, at(wed, 12).copy(charging = false, onBody = false)))
    }

    // Places and Wi-Fi

    private val workPlace = Place("Work", 29.9511, -90.0715, 150)
    private fun fix(lat: Double, lon: Double, acc: Float = 20f, age: Long = 60_000L) = Fix(lat, lon, acc, age)

    @Test fun notAtWork() {
        val r = Rules(places = Presence.NOT_WHEN, placeList = listOf(workPlace))
        assertEquals(Decision.Pause("at Work"), eval(r, at(wed, 12).copy(fix = fix(29.9512, -90.0716))))
        assertEquals(Decision.Listen, eval(r, at(wed, 12).copy(fix = fix(29.9611, -90.0715)))) // ~1.1 km north
    }

    @Test fun onlyAtHome() {
        val home = Place("Home", 30.0, -90.0, 100)
        val r = Rules(places = Presence.ONLY_WHEN, placeList = listOf(home))
        assertEquals(Decision.Listen, eval(r, at(wed, 12).copy(fix = fix(30.0, -90.0))))
        assertTrue(paused(eval(r, at(wed, 12).copy(fix = fix(30.01, -90.0)))))
    }

    @Test fun staleOrVagueFixesAreIgnored() {
        val r = Rules(places = Presence.NOT_WHEN, placeList = listOf(workPlace))
        val here = at(wed, 12)
        assertEquals(Decision.Listen, eval(r, here.copy(fix = fix(29.9511, -90.0715, age = 31 * 60_000L))))
        assertEquals(Decision.Listen, eval(r, here.copy(fix = fix(29.9511, -90.0715, acc = 900f))))
    }

    @Test fun placeRuleWithNoPlacesDoesNothing() {
        val r = Rules(places = Presence.ONLY_WHEN)
        assertTrue(!r.any)
        assertEquals(Decision.Listen, eval(r, at(wed, 12).copy(fix = fix(1.0, 1.0))))
    }

    @Test fun distanceIsRoughlyRight() {
        // One degree of latitude is about 111 km.
        val d = RuleEngine.distanceM(0.0, 0.0, 1.0, 0.0)
        assertTrue(d in 110_000.0..112_000.0)
    }

    @Test fun wifiMatchIgnoresQuotesCaseAndSpaces() {
        val r = Rules(wifi = Presence.NOT_WHEN, wifiList = listOf("Office WiFi"))
        val c = at(wed, 12).copy(wifiKnown = true)
        assertTrue(paused(eval(r, c.copy(ssid = "\"office wifi\""))))
        assertEquals(Decision.Listen, eval(r, c.copy(ssid = "Cafe")))
        assertEquals(Decision.Listen, eval(r, c.copy(ssid = null))) // not connected
    }

    @Test fun onlyOnSavedNetworkPausesElsewhereButNotWhenUnknown() {
        val r = Rules(wifi = Presence.ONLY_WHEN, wifiList = listOf("Home"))
        assertTrue(paused(eval(r, at(wed, 12).copy(wifiKnown = true, ssid = null))))
        assertEquals(Decision.Listen, eval(r, at(wed, 12).copy(wifiKnown = false)))
    }
}
