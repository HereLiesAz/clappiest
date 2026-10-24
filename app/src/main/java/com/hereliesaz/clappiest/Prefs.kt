package com.hereliesaz.clappiest

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** User settings. One file, three knobs. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("clappiest", Context.MODE_PRIVATE)

    var clapCount: Int
        get() = sp.getInt("clapCount", 3)
        set(v) = sp.edit { putInt("clapCount", v) }

    var sensitivity: Float
        get() = sp.getFloat("sensitivity", 0.5f)
        set(v) = sp.edit { putFloat("sensitivity", v) }

    /** Null means the system default alarm. */
    var alarmUri: Uri?
        get() = sp.getString("alarmUri", null)?.let(Uri::parse)
        set(v) = sp.edit { putString("alarmUri", v?.toString()) }
}
