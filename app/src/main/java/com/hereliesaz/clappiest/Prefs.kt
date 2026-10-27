package com.hereliesaz.clappiest

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.core.content.edit

/** User settings. One file, multiple knobs. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("clappiest", Context.MODE_PRIVATE)

    var clapCount: Int
        get() = sp.getInt("clapCount", 3)
        set(v) = sp.edit { putInt("clapCount", v) }

    var sensitivity: Float
        get() = sp.getFloat("sensitivity", 0.5f)
        set(v) = sp.edit { putFloat("sensitivity", v) }

    /** Whether two claps silence a sounding alarm. */
    var clapToStop: Boolean
        get() = sp.getBoolean("clapToStop", true)
        set(v) = sp.edit { putBoolean("clapToStop", v) }

    /** Whether the app may ask Google Play for a newer version when opened. */
    var updateCheck: Boolean
        get() = sp.getBoolean("updateCheck", true)
        set(v) = sp.edit { putBoolean("updateCheck", v) }

    var lastUpdateCheck: Long
        get() = sp.getLong("lastUpdateCheck", 0L)
        set(v) = sp.edit { putLong("lastUpdateCheck", v) }

    var snoozedKey: String?
        get() = sp.getString("snoozedKey", null)
        set(v) = sp.edit { putString("snoozedKey", v) }

    var snoozedUntil: Long
        get() = sp.getLong("snoozedUntil", 0L)
        set(v) = sp.edit { putLong("snoozedUntil", v) }

    var triggerMode: String
        get() = sp.getString("triggerMode", "claps") ?: "claps"
        set(v) = sp.edit { putString("triggerMode", v) }

    var customSoundBytes: ByteArray?
        get() {
            val base64 = sp.getString("customSound", null) ?: return null
            return runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull()
        }
        set(v) = sp.edit {
            val base64 = v?.let { Base64.encodeToString(it, Base64.DEFAULT) }
            putString("customSound", base64)
        }

    /** When the app may listen; see [Rules]. Stored as text by [RulesCodec]. */
    var rules: Rules
        get() = RulesCodec.decode(sp.getString("rules", null))
        set(v) = sp.edit { putString("rules", RulesCodec.encode(v)) }

    /** Null means the system default alarm. */
    var alarmUri: Uri?
        get() = sp.getString("alarmUri", null)?.let(Uri::parse)
        set(v) = sp.edit { putString("alarmUri", v?.toString()) }
}
