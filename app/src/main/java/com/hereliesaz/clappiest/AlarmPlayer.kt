package com.hereliesaz.clappiest

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Handler
import android.os.Looper

/**
 * Loops the chosen sound on the alarm stream at full volume, so silent/vibrate mode
 * cannot hide the phone. The prior alarm volume is restored on [stop].
 */
class AlarmPlayer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var savedVolume = -1
    private val handler = Handler(Looper.getMainLooper())
    private var replayGapMs = 0L
    private var onPhase: ((sounding: Boolean) -> Unit)? = null

    val isPlaying get() = player != null

    /**
     * @param replayGapMs when positive the sound does not loop seamlessly: it plays through,
     *   stays silent this long, then plays again. The gap is a clean moment to hear a clap.
     * @param onPhase called on the main thread with `true` when sound starts and `false` when
     *   a gap begins.
     */
    fun start(uri: Uri?, replayGapMs: Long = 0L, onPhase: ((sounding: Boolean) -> Unit)? = null) {
        if (player != null) return
        this.replayGapMs = replayGapMs
        this.onPhase = onPhase
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0,
        )
        player = create(uri) ?: create(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            ?: create(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        player?.let { p ->
            if (replayGapMs > 0) {
                p.isLooping = false
                p.setOnCompletionListener {
                    if (player === p) {
                        this.onPhase?.invoke(false)
                        handler.postDelayed(restart, replayGapMs)
                    }
                }
            }
            p.start()
        }
        this.onPhase?.invoke(true)
    }

    private val restart: Runnable = Runnable {
        player?.let { p ->
            runCatching { p.start() } // From the completed state this plays from the beginning.
            onPhase?.invoke(true)
        }
    }

    /** Mutes or restores the loop without stopping it, so the microphone can hear a clap. */
    fun mute(muted: Boolean) {
        val v = if (muted) 0f else 1f
        runCatching { player?.setVolume(v, v) }
    }

    fun stop() {
        handler.removeCallbacks(restart)
        onPhase = null
        player?.run { runCatching { stop() }; release() }
        player = null
        if (savedVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_ALARM, savedVolume, 0)
        savedVolume = -1
    }

    private fun create(uri: Uri?): MediaPlayer? {
        uri ?: return null
        return runCatching {
            MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
            }
        }.getOrNull()
    }
}
