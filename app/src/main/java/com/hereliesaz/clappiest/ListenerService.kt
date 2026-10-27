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
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
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
    /** The microphone could not be opened; shown in the notification until listening resumes. */
    private var listenerUnavailable = false

    /** Written on the main thread, read by the dismiss thread. */
    @Volatile private var windowOpen = false
    /** True only while the dismiss microphone is actually recording; never mute without it. */
    @Volatile private var dismissReady = false
    /** The alarm is between replays. */
    @Volatile private var gapQuiet = false
    /** `SystemClock.elapsedRealtime()` of the last change between sounding and silent. */
    @Volatile private var stateSince = 0L
    @Volatile private var dismissMode = MODE_WINDOWS

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
        main.removeCallbacksAndMessages(null)
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

        val thread = Thread({
            val me = Thread.currentThread()
            var matched = false
            val rec = openRecorder()
            try {
                if (rec != null && runCatching { rec.startRecording() }.isSuccess) {
                    main.post {
                        if (recordThread === me && listenerUnavailable) {
                            listenerUnavailable = false
                            refreshListeningNotification()
                        }
                    }
                    val buf = ShortArray(SAMPLE_RATE / 50)
                    while (recordThread === me) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n < 0) break
                        matched = when (detector) {
                            is ClapDetector -> detector.process(buf, n)
                            is CustomSoundDetector -> detector.process(buf, n)
                            else -> false
                        }
                        if (matched) break
                    }
                }
            } finally {
                rec?.let { runCatching { it.stop() }; it.release() }
            }
            // Dropped if stopListening() has since replaced or cleared this thread.
            main.post {
                if (recordThread !== me) return@post
                if (matched) onMatch() else onListenerFailed()
            }
        }, "clap-listener")
        recordThread = thread // Before start: the thread tests `recordThread === me`.
        thread.start()
    }

    /**
     * The microphone could not be opened, or died while listening (another app holds it, a call,
     * the privacy toggle). Say so, release the wake lock, and try again shortly while the screen
     * is dark. The next screen-off or settings refresh also starts afresh.
     */
    private fun onListenerFailed() {
        recordThread = null
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        listenerUnavailable = true
        refreshListeningNotification()
        main.removeCallbacks(retryListening)
        main.postDelayed(retryListening, RETRY_LISTEN_MS)
    }

    private val retryListening: Runnable = Runnable {
        if (running && !alarm.isPlaying && recordThread == null &&
            !getSystemService(PowerManager::class.java).isInteractive
        ) startListening()
    }

    private fun refreshListeningNotification() {
        if (running && !alarm.isPlaying) {
            getSystemService(NotificationManager::class.java).notify(NOTIF_LISTENING, listeningNotification())
        }
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
        main.removeCallbacks(retryListening)
        val t = recordThread
        recordThread = null
        t?.join(500)
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        if (listenerUnavailable) {
            listenerUnavailable = false
            refreshListeningNotification()
        }
    }

    private fun onMatch() {
        stopListening()
        if (prefs.clapToStop) {
            alarm.start(prefs.alarmUri, REPLAY_GAP_MS) { sounding ->
                gapQuiet = !sounding
                stateSince = SystemClock.elapsedRealtime()
            }
        } else {
            alarm.start(prefs.alarmUri)
        }
        getSystemService(NotificationManager::class.java).notify(NOTIF_ALARM, alarmNotification())
        if (prefs.clapToStop) beginDismissCycle()
    }

    /**
     * While the alarm sounds, listens for [DISMISS_CLAPS] claps.
     *
     * The alarm plays at full volume beside the microphone, so the microphone is opened with echo
     * cancellation where the phone offers it. The alarm also pauses [REPLAY_GAP_MS] between
     * replays. Whether cancellation actually works is measured, not assumed ([EchoGuard]):
     *
     *  - [MODE_CALIBRATING]: one forced silent window at [ALARM_LEAD_MS] gives the guard a
     *    quiet reference; claps are heard in silent moments only.
     *  - [MODE_CONTINUOUS]: the guard found the alarm cancelled; claps are heard over it and no
     *    more windows are forced.
     *  - [MODE_WINDOWS]: no cancellation, or it failed the test; the alarm is muted for a window
     *    every [ALARM_ON_MS] and claps are heard only in silent moments.
     */
    @SuppressLint("MissingPermission") // Checked in MainActivity before the service starts.
    private fun beginDismissCycle() {
        if (recordThread != null) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "clappiest:dismiss")
            .apply { acquire() }
        windowOpen = false
        stateSince = SystemClock.elapsedRealtime()
        val thread = Thread({
            val mic = openDismissRecorder() ?: return@Thread
            val rec = mic.record
            dismissMode = if (mic.mayCancel) MODE_CALIBRATING else MODE_WINDOWS
            val guard = EchoGuard()
            // Heard only in silent moments, against a quiet room.
            val windowDetector = ClapDetector(SAMPLE_RATE, DISMISS_CLAPS, prefs.sensitivity, finishOnTarget = true)
            // Heard over a cancelled alarm: judged against the alarm's own level.
            val liveDetector = ClapDetector(
                SAMPLE_RATE, DISMISS_CLAPS, prefs.sensitivity, finishOnTarget = true, fastFloor = true,
                // Over the alarm's residue a clap's sound is mixed with it, so it cannot be judged.
                verifyShape = false,
            )
            val buf = ShortArray(SAMPLE_RATE / 50)
            val blockMs = 1000 / 50
            var wasQuiet = false
            try {
                if (runCatching { rec.startRecording() }.isFailure) return@Thread
                dismissReady = true
                val me = Thread.currentThread()
                while (recordThread === me) {
                    val n = rec.read(buf, 0, buf.size)
                    if (n < 0) break
                    val quiet = windowOpen || gapQuiet
                    val settled = SystemClock.elapsedRealtime() - stateSince >= WINDOW_SETTLE_MS

                    if (mic.mayCancel && dismissMode == MODE_CALIBRATING) {
                        if (settled) guard.add(rmsOf(buf, n), blockMs, sounding = !quiet)
                        when (guard.verdict) {
                            EchoGuard.Verdict.CANCELLED -> dismissMode = MODE_CONTINUOUS
                            EchoGuard.Verdict.NOT_CANCELLED -> dismissMode = MODE_WINDOWS
                            EchoGuard.Verdict.UNKNOWN -> Unit
                        }
                    }

                    val hit = if (dismissMode == MODE_CONTINUOUS) {
                        // Always fed so its floor tracks the alarm; edges are ignored by `settled`.
                        liveDetector.process(buf, n) && settled
                    } else {
                        if (!quiet) { wasQuiet = false; continue } // hear nothing while it sounds
                        if (!wasQuiet) { wasQuiet = true; windowDetector.reset() }
                        settled && windowDetector.process(buf, n)
                    }
                    if (hit) {
                        main.post { silence() }
                        break
                    }
                }
            } finally {
                dismissReady = false
                runCatching { rec.stop() }
                rec.release()
                mic.echo?.release()
            }
        }, "clap-dismiss")
        recordThread = thread
        thread.start()
        main.postDelayed(openWindow, ALARM_LEAD_MS)
    }

    private class DismissMic(
        val record: AudioRecord,
        val echo: AcousticEchoCanceler?,
        /** The phone may be cancelling the alarm: worth testing. */
        val mayCancel: Boolean,
    )

    /**
     * Opens the microphone for use while the alarm sounds. VOICE_COMMUNICATION is the source
     * vendors tune echo cancellation for, so it is tried first; an explicit canceller is attached
     * where the phone offers one.
     */
    @SuppressLint("MissingPermission") // Checked in MainActivity before the service starts.
    private fun openDismissRecorder(): DismissMic? {
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val sources = listOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
        )
        for (source in sources) {
            val r = runCatching {
                AudioRecord(
                    source, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, SAMPLE_RATE / 5),
                )
            }.getOrNull() ?: continue
            if (r.state != AudioRecord.STATE_INITIALIZED) { r.release(); continue }
            val echo = if (AcousticEchoCanceler.isAvailable()) {
                runCatching {
                    AcousticEchoCanceler.create(r.audioSessionId)?.also { it.setEnabled(true) }
                }.getOrNull()
            } else null
            val mayCancel = source == MediaRecorder.AudioSource.VOICE_COMMUNICATION ||
                echo?.enabled == true
            return DismissMic(r, echo, mayCancel)
        }
        return null
    }

    private fun rmsOf(buf: ShortArray, n: Int): Float {
        var sum = 0.0
        for (i in 0 until n) { val v = buf[i].toDouble(); sum += v * v }
        return if (n > 0) kotlin.math.sqrt(sum / n).toFloat() else 0f
    }

    private val openWindow: Runnable = Runnable {
        if (!alarm.isPlaying || dismissMode == MODE_CONTINUOUS) return@Runnable
        if (!dismissReady) { // Nothing is listening: keep the alarm loud and look again later.
            main.postDelayed(openWindow, ALARM_ON_MS)
            return@Runnable
        }
        alarm.mute(true)
        windowOpen = true
        stateSince = SystemClock.elapsedRealtime()
        main.postDelayed(closeWindow, DISMISS_WINDOW_MS)
    }

    private val closeWindow: Runnable = Runnable {
        windowOpen = false
        stateSince = SystemClock.elapsedRealtime()
        if (!alarm.isPlaying) return@Runnable
        alarm.mute(false)
        if (dismissMode != MODE_CONTINUOUS) main.postDelayed(openWindow, ALARM_ON_MS)
    }

    private fun endDismissCycle() {
        main.removeCallbacks(openWindow)
        main.removeCallbacks(closeWindow)
        windowOpen = false
        gapQuiet = false
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
                when {
                    listenerUnavailable -> "Can't reach the microphone. Trying again."
                    prefs.triggerMode == "custom" -> "Listening for your custom sound."
                    else -> "${prefs.clapCount} claps and I'll scream."
                }
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

        private const val MODE_WINDOWS = 0
        private const val MODE_CALIBRATING = 1
        private const val MODE_CONTINUOUS = 2

        /** Wait before reopening a microphone that failed. */
        private const val RETRY_LISTEN_MS = 10_000L

        /** Silence between replays of the alarm sound while clap-to-silence is on. */
        private const val REPLAY_GAP_MS = 1_000L

        /** Claps that silence the alarm. */
        private const val DISMISS_CLAPS = 2
        /** Unbroken alarm before the first listening window. */
        private const val ALARM_LEAD_MS = 4_000L
        /** Alarm between listening windows. */
        private const val ALARM_ON_MS = 3_500L
        /** Muted window; room for the user to react and clap twice. */
        private const val DISMISS_WINDOW_MS = 3_000L
        /** Ignored after the alarm starts or stops while the speaker's tail dies. */
        private const val WINDOW_SETTLE_MS = 400L
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
