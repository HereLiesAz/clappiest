package com.hereliesaz.clappiest

import org.junit.Assert.assertEquals
import org.junit.Test

class RulesCodecTest {
    private val full = Rules(
        schedule = Presence.NOT_WHEN, days = 0b0011111, startMin = 540, endMin = 1020,
        charging = Presence.ONLY_WHEN, batteryMin = 15, headphones = Presence.NOT_WHEN, onBody = Presence.NOT_WHEN,
        places = Presence.NOT_WHEN, placeList = listOf(Place("Work", 29.9511, -90.0715, 150), Place("Gym", -33.5, 151.25, 250)),
        wifi = Presence.NOT_WHEN, wifiList = listOf("Office WiFi", "Cafe"),
    )

    @Test fun roundTrips() = assertEquals(full, RulesCodec.decode(RulesCodec.encode(full)))

    @Test fun defaultsRoundTrip() = assertEquals(Rules(), RulesCodec.decode(RulesCodec.encode(Rules())))

    @Test fun nullAndEmptyGiveDefaults() {
        assertEquals(Rules(), RulesCodec.decode(null))
        assertEquals(Rules(), RulesCodec.decode(""))
    }

    @Test fun garbageLinesAreSkippedAndGoodOnesKept() {
        val text = "nonsense\nschedule=MAYBE\nbatteryMin=abc\ncharging=NOT_WHEN\nplace=short\nbatteryMin=999\n"
        val r = RulesCodec.decode(text)
        assertEquals(Presence.ANY, r.schedule)
        assertEquals(Presence.NOT_WHEN, r.charging)
        assertEquals(100, r.batteryMin) // out of range clamps
        assertEquals(emptyList<Place>(), r.placeList)
    }

    @Test fun namesCannotInjectSeparators() {
        val r = Rules(placeList = listOf(Place("A\nB\u001fC", 1.0, 2.0, 100)), wifiList = listOf("x\ny"))
        val back = RulesCodec.decode(RulesCodec.encode(r))
        assertEquals(1, back.placeList.size)
        assertEquals("A B C", back.placeList[0].name)
        assertEquals(listOf("x y"), back.wifiList)
    }

    @Test fun listsAreCapped() {
        val r = Rules(
            placeList = (1..9).map { Place("P$it", 0.0, 0.0, 100) },
            wifiList = (1..20).map { "n$it" },
        )
        val back = RulesCodec.decode(RulesCodec.encode(r))
        assertEquals(RulesCodec.MAX_PLACES, back.placeList.size)
        assertEquals(RulesCodec.MAX_NETWORKS, back.wifiList.size)
    }
}
