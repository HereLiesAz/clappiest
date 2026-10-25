package com.hereliesaz.clappiest

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that owns the microphone.
 *
 * Lifecycle of a find:
 *  screen off  -> start recording, feed [ClapDetector] or [CustomSoundDetector]
 *  screen on   -> stop recording (the phone is evidently not lost)
 *  match       -> stop recording, sound [AlarmPlayer], post alarm notification
 *  alarming    -> pulse: sound, then a muted window in which two claps silence it
 *  unlock / Stop action / two claps -> silence; resume listening when the screen is dark
 *
 * The alarm is muted during each window because it plays at full volume beside the microphone
 * and would otherwise drown out a clap.
 *
 * Must be started from the foreground (Android 11+ microphone rule). Hence START_NOT_STICKY:
 * the system may not restart a microphone service from the background anyway.
 */
class ListenerService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var alarm: AlarmPlayer
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var recordThread: Thread? = null

    /** Written on the main thread, read by the dismiss thread. */
    @Volatile private var windowOpen = false
    @Volatile private var windowId = 0
    /** True only while the dismiss microphone is actually recording; never mute without it. */
    @Volatile private var dismissReady = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> if (!alarm.isPlaying) startListening()
                // A sounding alarm keeps its own listener; only the find-the-phone listener stops.
                Intent.ACTION_SCREEN_ON -> if (!alarm.isPlaying) stopListening()
                Intent.ACTION_USER_PRESENT -> silence()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        alarm = AlarmPlayer(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SILENCE) {
            silence()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this, NOTIF_LISTENING, listeningNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0,
        )
        if (!running) {
            ContextCompat.registerReceiver(
                this, screenReceiver,
                IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_OFF)
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_USER_PRESENT)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            running = true
        }
        // A sounding alarm must not be interrupted by a settings refresh.
        if (alarm.isPlaying) return START_NOT_STICKY
        // Settings may have changed; restart the detector with fresh values.
        stopListening()
        if (!getSystemService(PowerManager::class.java).isInteractive) startListening()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (running) unregisterReceiver(screenReceiver)
        running = false
        stopListening()
        silence()
        super.onDestroy()
    }

    @SuppressLint("MissingPermission") // Checked in MainActivity before the service starts.
    private fun startListening() {
        if (recordThread != null) return
        val detector: Any = if (prefs.triggerMode == "custom") {
            val bytes = prefs.customSoundBytes
            val shorts = if (bytes != null) {
                ShortArray(bytes.size / 2) { i ->
                    ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                }
            } else {
                ShortArray(0)
            }
            CustomSoundDetector(shorts, prefs.sensitivity)
        } else {
            ClapDetector(SAMPLE_RATE, prefs.clapCount, prefs.sensitivity)
        }

        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clappiest:listen")
            .apply { acquire() }

        recordThread = Thread({
            val rec = openRecorder() ?: return@Thread
            val buf = ShortArray(SAMPLE_RATE / 50)
            try {
                rec.startRecording()
                val me = Thread.currentThread()
                while (recordThread === me) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    val matched = when (detector) {
                        is ClapDetector -> detector.process(buf, n)
                        is CustomSoundDetector -> detector.process(buf, n)
                        else -> false
                    }
                    if (matched) {
                        main.post { onMatch() }
                        break
                    }
                }
            } finally {
                runCatching { rec.stop() }
                rec.release()
            }
        }, "clap-listener").apply { start() }
    }

    /**
     * Opens the microphone. VOICE_RECOGNITION skips the noise suppression and AGC that MIC applies
     * on most phones; both flatten exactly the transients a clap or whistle is made of.
     */
    @SuppressLint("MissingPermission") // Checked in MainActivity before the service starts.
    private fun openRecorder(): AudioRecord? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        return listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        ).firstNotNullOfOrNull { source ->
            runCatching {
                AudioRecord(
                    source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, SAMPLE_RATE / 5),
                )
            }.getOrNull()?.let { r ->
                if (r.state == AudioRecord.STATE_INITIALIZED) r else { r.release(); null }
            }
        }
    }

    private fun stopListening() {
        val t = recordThread ?: return
        recordThread = null
        t.join(500)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    private fun onMatch() {
        stopListening()
        alarm.start(prefs.alarmUri)
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALARM, alarmNotification())
        if (prefs.clapToStop) beginDismissCycle()
    }

    /**
     * While the alarm sounds, listens for [DISMISS_CLAPS] claps. The alarm plays [ALARM_LEAD_MS]
     * unbroken so the phone is easy to locate, then alternates a muted listening window with
     * [ALARM_ON_MS] of sound.
     */
    @SuppressLint("MissingPermission") // Checked in MainActivity before the service starts.
    private fun beginDismissCycle() {
        if (recordThread != null) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clappiest:dismiss")
            .apply { acquire() }
        windowOpen = false
        recordThread = Thread({
            val rec = openRecorder() ?: return@Thread
            val detector = ClapDetector(SAMPLE_RATE, DISMISS_CLAPS, prefs.sensitivity, finishOnTarget = true)
            val buf = ShortArray(SAMPLE_RATE / 50)
            var seenWindow = -1
            var settleSamples = 0
            try {
                rec.startRecording()
                dismissReady = true
                val me = Thread.currentThread()
                while (recordThread === me) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    if (!windowOpen) { seenWindow = -1; continue } // keep draining; hear nothing
                    val id = windowId
                    if (id != seenWindow) {
                        // New window: forget the last one and skip the speaker's dying tail.
                        seenWindow = id
                        detector.reset()
                        settleSamples = SAMPLE_RATE * WINDOW_SETTLE_MS / 1000
                    }
                    if (settleSamples > 0) { settleSamples -= n; continue }
                    if (detector.process(buf, n)) {
                        main.post { silence() }
                        break
                    }
                }
            } finally {
                dismissReady = false
                runCatching { rec.stop() }
                rec.release()
            }
        }, "clap-dismiss").apply { start() }
        main.postDelayed(openWindow, ALARM_LEAD_MS)
    }

    private val openWindow: Runnable = Runnable {
        if (!alarm.isPlaying) return@Runnable
        if (!dismissReady) { // Nothing is listening: keep the alarm loud and look again later.
            main.postDelayed(openWindow, ALARM_ON_MS)
            return@Runnable
        }
        alarm.mute(true)
        windowId++
        windowOpen = true
        main.postDelayed(closeWindow, DISMISS_WINDOW_MS)
    }

    private val closeWindow: Runnable = Runnable {
        windowOpen = false
        if (!alarm.isPlaying) return@Runnable
        alarm.mute(false)
        main.postDelayed(openWindow, ALARM_ON_MS)
    }

    private fun endDismissCycle() {
        main.removeCallbacks(openWindow)
        main.removeCallbacks(closeWindow)
        windowOpen = false
        dismissReady = false
        stopListening()
    }

    private fun silence() {
        if (!alarm.isPlaying) return
        endDismissCycle()
        alarm.stop()
        getSystemService(NotificationManager::class.java).cancel(NOTIF_ALARM)
        if (running && !getSystemService(PowerManager::class.java).isInteractive) startListening()
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_LISTEN, "Listening", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALARM, "Found", NotificationManager.IMPORTANCE_HIGH)
                .apply { setSound(null, null) } // The AlarmPlayer is the sound.
        )
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
    )

    private fun silenceIntent() = PendingIntent.getService(
        this, 1, Intent(this, ListenerService::class.java).setAction(ACTION_SILENCE),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun listeningNotification(): Notification =
        NotificationCompat.Builder(this, CH_LISTEN)
            .setSmallIcon(R.drawable.ic_clap)
            .setContentTitle("Listening in the dark")
            .setContentText(
                if (prefs.triggerMode == "custom") "Listening for your custom sound."
                else "${prefs.clapCount} claps and I'll scream."
            )
            .setContentIntent(openApp())
            .setOngoing(true)
            .build()

    private fun alarmNotification(): Notification =
        NotificationCompat.Builder(this, CH_ALARM)
            .setSmallIcon(R.drawable.ic_clap)
            .setContentTitle("Here I am")
            .setContentText(
                if (prefs.clapToStop) "Unlock, tap Stop, or clap twice when it goes quiet."
                else "Unlock or tap Stop."
            )
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentIntent(silenceIntent())
            .setDeleteIntent(silenceIntent())
            .addAction(0, "Stop", silenceIntent())
            .build()

    companion object {
        const val SAMPLE_RATE = 16_000

        /** Claps that silence the alarm. */
        private const val DISMISS_CLAPS = 2
        /** Unbroken alarm before the first listening window. */
        private const val ALARM_LEAD_MS = 4_000L
        /** Alarm between listening windows. */
        private const val ALARM_ON_MS = 3_500L
        /** Muted window; room for the user to react and clap twice. */
        private const val DISMISS_WINDOW_MS = 3_000L
        /** Discarded at the start of a window while the speaker's tail dies. */
        private const val WINDOW_SETTLE_MS = 400
        private const val CH_LISTEN = "listen"
        private const val CH_ALARM = "alarm"
        private const val NOTIF_LISTENING = 1
        private const val NOTIF_ALARM = 2
        private const val ACTION_SILENCE = "com.hereliesaz.clappiest.SILENCE"

        /** Observable by the UI. */
        var running by mutableStateOf(false)
            private set

        fun start(context: Context) =
            ContextCompat.startForegroundService(context, Intent(context, ListenerService::class.java))

        fun stop(context: Context) =
            context.stopService(Intent(context, ListenerService::class.java))
    }
}
