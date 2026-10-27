package com.hereliesaz.clappiest

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** How much location the user has granted. Only the place and Wi-Fi rules ever use it. */
enum class LocationAccess {
    NONE,

    /** Only while the app is on screen: not enough to check a place with the screen off. */
    WHILE_IN_USE,

    /** "Allow all the time": what the place and Wi-Fi rules need. */
    ALWAYS;

    companion object {
        fun of(context: Context): LocationAccess {
            fun granted(p: String) = ContextCompat.checkSelfPermission(context, p) == PackageManager.PERMISSION_GRANTED
            if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) return NONE
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ALWAYS // Foreground permission covers background before Android 10.
            return if (granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) ALWAYS else WHILE_IN_USE
        }
    }
}
