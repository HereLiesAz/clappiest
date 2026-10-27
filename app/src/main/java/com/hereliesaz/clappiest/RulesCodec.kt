package com.hereliesaz.clappiest

/**
 * Stores [Rules] as plain text, one `key=value` per line, so it needs no JSON library and tests on
 * the JVM. Unknown or malformed lines are skipped, and a missing key keeps its default: an old or
 * damaged value never stops the app from listening.
 */
object RulesCodec {
    private const val SEP = '\u001f'

    fun encode(r: Rules): String = buildString {
        line("schedule", r.schedule.name)
        line("days", r.days)
        line("startMin", r.startMin)
        line("endMin", r.endMin)
        line("charging", r.charging.name)
        line("batteryMin", r.batteryMin)
        line("headphones", r.headphones.name)
        line("onBody", r.onBody.name)
        line("places", r.places.name)
        r.placeList.forEach { line("place", "${clean(it.name)}$SEP${it.lat}$SEP${it.lon}$SEP${it.radiusM}") }
        line("wifi", r.wifi.name)
        r.wifiList.forEach { line("ssid", clean(it)) }
    }

    fun decode(text: String?): Rules {
        var r = Rules()
        val places = ArrayList<Place>()
        val ssids = ArrayList<String>()
        text?.lineSequence()?.forEach { raw ->
            val i = raw.indexOf('=')
            if (i <= 0) return@forEach
            val key = raw.substring(0, i)
            val v = raw.substring(i + 1)
            runCatching {
                when (key) {
                    "schedule" -> r = r.copy(schedule = Presence.valueOf(v))
                    "days" -> r = r.copy(days = v.toInt() and Rules.ALL_DAYS)
                    "startMin" -> r = r.copy(startMin = v.toInt().coerceIn(0, 1439))
                    "endMin" -> r = r.copy(endMin = v.toInt().coerceIn(0, 1439))
                    "charging" -> r = r.copy(charging = Presence.valueOf(v))
                    "batteryMin" -> r = r.copy(batteryMin = v.toInt().coerceIn(0, 100))
                    "headphones" -> r = r.copy(headphones = Presence.valueOf(v))
                    "onBody" -> r = r.copy(onBody = Presence.valueOf(v))
                    "places" -> r = r.copy(places = Presence.valueOf(v))
                    "place" -> {
                        val f = v.split(SEP)
                        places += Place(f[0], f[1].toDouble(), f[2].toDouble(), f[3].toInt().coerceIn(MIN_RADIUS_M, MAX_RADIUS_M))
                    }
                    "wifi" -> r = r.copy(wifi = Presence.valueOf(v))
                    "ssid" -> if (v.isNotBlank()) ssids += v
                }
            }
        }
        return r.copy(placeList = places.take(MAX_PLACES), wifiList = ssids.take(MAX_NETWORKS))
    }

    private fun StringBuilder.line(k: String, v: Any) { append(k).append('=').append(v).append('\n') }

    /** Names must not contain the separators. */
    private fun clean(s: String) = s.replace(SEP, ' ').replace('\n', ' ').replace('\r', ' ').trim()

    const val MAX_PLACES = 5
    const val MAX_NETWORKS = 8
    const val MIN_RADIUS_M = 50
    const val MAX_RADIUS_M = 2000
}
