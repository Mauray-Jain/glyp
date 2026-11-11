package com.mauray.glyp.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import com.mauray.glyp.studio.Clock
import java.io.File

/**
 * [Clock] adapter for the song file; blocks while preparing, so create it off the main thread.
 * Holds audio focus while playing, so other music pauses for the preview, and pauses itself
 * when a call or another app takes the audio.
 */
class MediaPlayerClock(context: Context, file: File) : Clock {

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private val player = MediaPlayer().apply {
        setAudioAttributes(attributes)
        setDataSource(file.absolutePath)
        prepare()
    }

    private val audio = context.getSystemService(AudioManager::class.java)
    private var onInterrupt: () -> Unit = {}

    private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener({ change ->
            // Ducking is left to the system; anything stronger stops the preview.
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                if (player.isPlaying) {
                    pause()
                    onInterrupt()
                }
            }
        }, Handler(Looper.getMainLooper()))
        .build()

    override val nowNanos: Long get() = System.nanoTime()
    override val songMs: Long get() = player.currentPosition.toLong()
    override val playing: Boolean get() = player.isPlaying

    override fun play(fromMs: Long) {
        audio.requestAudioFocus(focus)
        player.seekTo(fromMs, MediaPlayer.SEEK_CLOSEST)
        player.start()
    }

    override fun pause() {
        if (player.isPlaying) player.pause()
        audio.abandonAudioFocusRequest(focus)
    }

    // Created without a Looper of its own, MediaPlayer delivers this on the main thread.
    override fun setOnEnd(listener: () -> Unit) = player.setOnCompletionListener {
        audio.abandonAudioFocusRequest(focus)
        listener()
    }

    override fun setOnInterrupt(listener: () -> Unit) {
        onInterrupt = listener
    }

    override fun release() {
        audio.abandonAudioFocusRequest(focus)
        player.release()
    }
}
