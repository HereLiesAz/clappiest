package com.hereliesaz.clappiest

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** How a rule applies: ignored, required to hold, or required not to hold. */
enum class Presence { ANY, ONLY_WHEN, NOT_WHEN }

/** A saved location. [radiusM] is how far from the centre still counts as "there". */
data class Place(val name: String, val lat: Double, val lon: Double, val radiusM: Int)

/**
 * When the app may listen. Every enabled rule must allow it; a rule left at [Presence.ANY] (or
 * [batteryMin] 0) is ignored. Pure data and logic, no Android dependencies, so it is unit-testable.
 *
 * Rules only decide whether the find-the-phone listener runs. A sounding alarm is never affected.
 */
data class Rules(
    /** Listen only / never during the window below. */
    val schedule: Presence = Presence.ANY,
    /** Days the window applies to; bit 0 = Monday .. bit 6 = Sunday. */
    val days: Int = WEEKDAYS,
    val startMin: Int = 9 * 60,
    val endMin: Int = 17 * 60,
    val charging: Presence = Presence.ANY,
    /** Do not listen below this battery percentage; 0 is off. */
    val batteryMin: Int = 0,
    /** Wired or Bluetooth audio output connected. */
    val headphones: Presence = Presence.ANY,
    /** The phone seems to be carried or in a pocket ([BodyEstimator]). */
    val onBody: Presence = Presence.ANY,
    val places: Presence = Presence.ANY,
    val placeList: List<Place> = emptyList(),
    val wifi: Presence = Presence.ANY,
    val wifiList: List<String> = emptyList(),
) {
    /** True when any rule is switched on. */
    val any get() = schedule != Presence.ANY || charging != Presence.ANY || batteryMin > 0 ||
        headphones != Presence.ANY || onBody != Presence.ANY ||
        (places != Presence.ANY && placeList.isNotEmpty()) ||
        (wifi != Presence.ANY && wifiList.isNotEmpty())

    /** Number of switched-on rules. */
    val count get() = listOf(
        schedule != Presence.ANY, charging != Presence.ANY, batteryMin > 0,
        headphones != Presence.ANY, onBody != Presence.ANY,
        places != Presence.ANY && placeList.isNotEmpty(),
        wifi != Presence.ANY && wifiList.isNotEmpty(),
    ).count { it }

    val needsLocation get() = (places != Presence.ANY && placeList.isNotEmpty()) ||
        (wifi != Presence.ANY && wifiList.isNotEmpty())

    companion object {
        const val WEEKDAYS = 0b0011111
        const val ALL_DAYS = 0b1111111
    }
}

/** A position fix. [ageMs] is how long ago it was taken. */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val ageMs: Long)

/**
 * What is true right now. `null` means unknown, and an unknown condition never pauses listening:
 * a lost phone matters more than a rule that cannot be checked.
 */
data class Conditions(
    /** 0 = Monday .. 6 = Sunday. */
    val day: Int,
    val minuteOfDay: Int,
    val charging: Boolean? = null,
    val batteryPct: Int? = null,
    val headphones: Boolean? = null,
    val onBody: Boolean? = null,
    val fix: Fix? = null,
    /** Whether the Wi-Fi state could be read at all. */
    val wifiKnown: Boolean = false,
    /** Name of the connected Wi-Fi network, or null when not connected. */
    val ssid: String? = null,
)

sealed class Decision {
    data object Listen : Decision()
    data class Pause(val reason: String) : Decision()
}

object RuleEngine {
    /** A fix older than this, or less accurate than [MAX_ACCURACY_M], is treated as unknown. */
    const val MAX_FIX_AGE_MS = 30 * 60_000L
    const val MAX_ACCURACY_M = 500f

    fun evaluate(rules: Rules, c: Conditions): Decision {
        if (rules.schedule != Presence.ANY && rules.days != 0) {
            val inside = inWindow(rules, c.day, c.minuteOfDay)
            if (rules.schedule == Presence.ONLY_WHEN && !inside) return Decision.Pause("outside its hours")
            if (rules.schedule == Presence.NOT_WHEN && inside) return Decision.Pause("scheduled off")
        }
        c.charging?.let {
            if (rules.charging == Presence.ONLY_WHEN && !it) return Decision.Pause("not charging")
            if (rules.charging == Presence.NOT_WHEN && it) return Decision.Pause("charging")
        }
        c.batteryPct?.let {
            if (rules.batteryMin > 0 && it < rules.batteryMin) return Decision.Pause("battery below ${rules.batteryMin}%")
        }
        c.headphones?.let {
            if (rules.headphones == Presence.ONLY_WHEN && !it) return Decision.Pause("no headphones")
            if (rules.headphones == Presence.NOT_WHEN && it) return Decision.Pause("headphones connected")
        }
        c.onBody?.let {
            if (rules.onBody == Presence.ONLY_WHEN && !it) return Decision.Pause("not on you")
            if (rules.onBody == Presence.NOT_WHEN && it) return Decision.Pause("on you")
        }
        if (rules.places != Presence.ANY && rules.placeList.isNotEmpty()) {
            val fix = c.fix
            if (fix != null && fix.ageMs <= MAX_FIX_AGE_MS && fix.accuracyM <= MAX_ACCURACY_M) {
                val here = rules.placeList.firstOrNull { distanceM(fix.lat, fix.lon, it.lat, it.lon) <= it.radiusM }
                if (rules.places == Presence.ONLY_WHEN && here == null) return Decision.Pause("not at a saved place")
                if (rules.places == Presence.NOT_WHEN && here != null) return Decision.Pause("at ${here.name}")
            }
        }
        if (rules.wifi != Presence.ANY && rules.wifiList.isNotEmpty() && c.wifiKnown) {
            val match = c.ssid?.let { s -> rules.wifiList.firstOrNull { sameNetwork(it, s) } }
            if (rules.wifi == Presence.ONLY_WHEN && match == null) return Decision.Pause("not on a saved network")
            if (rules.wifi == Presence.NOT_WHEN && match != null) return Decision.Pause("on $match")
        }
        return Decision.Listen
    }

    /**
     * Whether [day]/[minute] falls inside the window. A window that ends before it starts runs past
     * midnight and belongs to the day it started on. Equal start and end mean the whole day.
     */
    fun inWindow(r: Rules, day: Int, minute: Int): Boolean {
        fun on(d: Int) = ((r.days shr ((d % 7 + 7) % 7)) and 1) == 1
        return when {
            r.startMin == r.endMin -> on(day)
            r.startMin < r.endMin -> on(day) && minute >= r.startMin && minute < r.endMin
            else -> (on(day) && minute >= r.startMin) || (on(day - 1) && minute < r.endMin)
        }
    }

    /** Minutes from now until the window next opens or closes; null when no schedule is set. */
    fun minutesToNextEdge(r: Rules, minuteOfDay: Int): Int? {
        if (r.schedule == Presence.ANY || r.days == 0 || r.startMin == r.endMin) return null
        fun until(edge: Int) = ((edge - minuteOfDay) + 1440) % 1440
        return listOf(until(r.startMin), until(r.endMin)).map { if (it == 0) 1440 else it }.min()
    }

    fun sameNetwork(a: String, b: String) = clean(a).equals(clean(b), ignoreCase = true)
    private fun clean(s: String) = s.trim().removeSurrounding("\"")

    /** Great-circle distance in metres. */
    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6_371_000.0
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dp = p2 - p1; val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * r * asin(min(1.0, sqrt(a)))
    }
}
