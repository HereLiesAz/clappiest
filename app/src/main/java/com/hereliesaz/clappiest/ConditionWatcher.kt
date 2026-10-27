package com.hereliesaz.clappiest

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.Calendar

/**
 * Keeps an up-to-date picture of the conditions the user's [Rules] ask about, and calls
 * [onChange] on the main thread when one may have changed. Only what the current rules need is
 * watched, and the costly sources (motion sensors, location) only while the screen is off, which
 * is the only time the app listens.
 *
 * Anything that cannot be read (no permission, no sensor) is reported as unknown, which never
 * pauses listening ([RuleEngine]).
 */
class ConditionWatcher(context: Context, private val onChange: () -> Unit) {
    private val ctx = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var rules = Rules()
    private var started = false
    private var screenOff = false

    private val estimator = BodyEstimator()
    private var lastOnBody = false
    private var sensing = false
    private var sensorLock: PowerManager.WakeLock? = null

    private var lastLocation: Location? = null
    private var locating = false
    private var wifiWatching = false
    private var audioWatching = false
    private var batteryWatching = false
    private var batteryCharging: Boolean? = null
    private var batteryPct: Int? = null

    // Registered once while started: the schedule's alarm and clock changes.
    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { armTick(); onChange() }
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (readBattery(i)) onChange() }
    }

    private val audioCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>?) = onChange()
        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>?) = onChange()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { main.post { onChange() } }
        override fun onLost(network: Network) { main.post { onChange() } }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post { onChange() } }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) { lastLocation = location; onChange() }
        @Deprecated("Needed below API 29.") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val now = SystemClock.elapsedRealtime()
            when (e.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> estimator.onAccel(e.values[0], e.values[1], e.values[2], now)
                Sensor.TYPE_PROXIMITY -> estimator.onProximity(e.values[0] < minOf(e.sensor.maximumRange, NEAR_CM))
            }
            val now2 = estimator.onBody(now)
            if (now2 != lastOnBody) { lastOnBody = now2; onChange() }
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Begin watching for [r]. Safe to call again with new rules. */
    fun start(r: Rules) {
        if (!started) {
            started = true
            ContextCompat.registerReceiver(
                ctx, tickReceiver,
                IntentFilter().apply {
                    addAction(ACTION_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                    addAction(Intent.ACTION_DATE_CHANGED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
        rules = r
        update()
    }

    fun stop() {
        if (!started) return
        started = false
        rules = Rules()
        update()
        runCatching { ctx.unregisterReceiver(tickReceiver) }
    }

    fun setScreenOff(off: Boolean) {
        if (screenOff == off) return
        screenOff = off
        // The screen was just in use, so the phone was in a hand. See BodyEstimator.noteMotion.
        if (off) estimator.noteMotion(SystemClock.elapsedRealtime())
        update()
    }

    /** What is true now. */
    fun snapshot(): Conditions {
        val cal = Calendar.getInstance()
        val (wifiKnown, ssid) = readWifi()
        return Conditions(
            day = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7, // Calendar: Sunday = 1; here Monday = 0.
            minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE),
            charging = batteryCharging,
            batteryPct = batteryPct,
            headphones = if (rules.headphones != Presence.ANY) headphonesNow() else null,
            onBody = if (sensing) estimator.onBody(SystemClock.elapsedRealtime()) else null,
            fix = lastLocation?.let {
                Fix(it.latitude, it.longitude, it.accuracy,
                    (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000L)
            },
            wifiKnown = wifiKnown,
            ssid = ssid,
        )
    }

    // Registration follows the rules and the screen.

    @SuppressLint("MissingPermission") // Checked through LocationAccess before use.
    private fun update() {
        val active = started
        setBattery(active && (rules.charging != Presence.ANY || rules.batteryMin > 0))
        setAudio(active && rules.headphones != Presence.ANY)
        setWifi(active && rules.wifi != Presence.ANY && rules.wifiList.isNotEmpty())
        setSensing(active && screenOff && rules.onBody != Presence.ANY)
        setLocating(active && screenOff && rules.places != Presence.ANY && rules.placeList.isNotEmpty())
        if (active) armTick() else cancelTick()
    }

    private fun setBattery(on: Boolean) {
        if (on == batteryWatching) return
        batteryWatching = on
        if (on) {
            val sticky = ContextCompat.registerReceiver(
                ctx, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            sticky?.let { readBattery(it) }
        } else {
            runCatching { ctx.unregisterReceiver(batteryReceiver) }
            batteryCharging = null; batteryPct = null
        }
    }

    /** Returns true when charging or the percentage changed. */
    private fun readBattery(i: Intent): Boolean {
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else null
        val changed = charging != batteryCharging || pct != batteryPct
        batteryCharging = charging; batteryPct = pct
        return changed
    }

    private fun setAudio(on: Boolean) {
        if (on == audioWatching) return
        audioWatching = on
        val am = ctx.getSystemService(AudioManager::class.java)
        if (on) am.registerAudioDeviceCallback(audioCallback, main) else am.unregisterAudioDeviceCallback(audioCallback)
    }

    private fun headphonesNow(): Boolean {
        val am = ctx.getSystemService(AudioManager::class.java)
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type in HEADSET_TYPES || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }

    private fun setWifi(on: Boolean) {
        if (on == wifiWatching) return
        wifiWatching = on
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        runCatching {
            if (on) {
                cm.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), networkCallback,
                )
            } else {
                cm.unregisterNetworkCallback(networkCallback)
            }
        }
    }

    /** (readable, ssid). The name needs location permission; without it the state is unknown. */
    @Suppress("DEPRECATION")
    private fun readWifi(): Pair<Boolean, String?> {
        if (!(rules.wifi != Presence.ANY && rules.wifiList.isNotEmpty())) return false to null
        if (LocationAccess.of(ctx) != LocationAccess.ALWAYS) return false to null
        val wm = ctx.getSystemService(WifiManager::class.java) ?: return false to null
        return runCatching {
            if (!wm.isWifiEnabled) return@runCatching true to null
            val info = wm.connectionInfo ?: return@runCatching false to null
            val ssid = info.ssid
            when {
                info.networkId == -1 -> true to null
                ssid == null || ssid == WifiManager.UNKNOWN_SSID -> false to null
                else -> true to ssid
            }
        }.getOrDefault(false to null)
    }

    private fun setSensing(on: Boolean) {
        if (on == sensing) return
        val sm = ctx.getSystemService(SensorManager::class.java)
        if (on) {
            val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return // Unknown, never paused.
            sensing = true
            sensorLock = ctx.getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clappiest:body").apply { acquire() }
            sm.registerListener(sensorListener, accel, SAMPLE_US, main)
            sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sm.registerListener(sensorListener, it, SensorManager.SENSOR_DELAY_NORMAL, main) }
        } else {
            sensing = false
            sm.unregisterListener(sensorListener)
            sensorLock?.takeIf { it.isHeld }?.release()
            sensorLock = null
            lastOnBody = false
        }
    }

    @SuppressLint("MissingPermission") // Checked through LocationAccess before use.
    private fun setLocating(on: Boolean) {
        val allowed = on && LocationAccess.of(ctx) != LocationAccess.NONE
        if (allowed == locating) return
        locating = allowed
        val lm = ctx.getSystemService(LocationManager::class.java) ?: return
        runCatching {
            if (allowed) {
                lastLocation = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
                    .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
                    .maxByOrNull { it.elapsedRealtimeNanos } ?: lastLocation
                if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    lm.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_EVERY_MS, LOCATION_MIN_M, locationListener, main.looper)
                }
                onChange()
            } else {
                lm.removeUpdates(locationListener)
            }
        }
    }

    // The schedule: one inexact alarm at the next time its window opens or closes.

    private fun tickIntent() = PendingIntent.getBroadcast(
        ctx, 2, Intent(ACTION_TICK).setPackage(ctx.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun armTick() {
        val cal = Calendar.getInstance()
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val mins = RuleEngine.minutesToNextEdge(rules, minuteOfDay) ?: return cancelTick()
        val intoMinuteMs = cal.get(Calendar.SECOND) * 1000L + cal.get(Calendar.MILLISECOND)
        val at = System.currentTimeMillis() + mins * 60_000L - intoMinuteMs + 1_000L
        // Inexact on purpose: exact alarms need a permission, and a few minutes' drift is fine here.
        ctx.getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, tickIntent())
    }

    private fun cancelTick() {
        ctx.getSystemService(AlarmManager::class.java).cancel(tickIntent())
    }

    companion object {
        private const val ACTION_TICK = "com.hereliesaz.clappiest.RULES_TICK"
        private const val SAMPLE_US = 100_000 // 10 Hz
        private const val NEAR_CM = 5f
        private const val LOCATION_EVERY_MS = 5 * 60_000L
        private const val LOCATION_MIN_M = 50f
        private val HEADSET_TYPES = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
        )
    }
}
