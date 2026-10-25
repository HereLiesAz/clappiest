package com.hereliesaz.clappiest

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri

/**
 * Loops the chosen sound on the alarm stream at full volume, so silent/vibrate mode
 * cannot hide the phone. The prior alarm volume is restored on [stop].
 */
class AlarmPlayer(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var savedVolume = -1

    val isPlaying get() = player != null

    fun start(uri: Uri?) {
        if (player != null) return
        savedVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
        audio.setStreamVolume(
            AudioManager.STREAM_ALARM,
            audio.getStreamMaxVolume(AudioManager.STREAM_ALARM),
            0,
        )
        player = create(uri) ?: create(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))
            ?: create(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
        player?.start()
    }

    /** Mutes or restores the loop without stopping it, so the microphone can hear a clap. */
    fun mute(muted: Boolean) {
        val v = if (muted) 0f else 1f
        runCatching { player?.setVolume(v, v) }
    }

    fun stop() {
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
