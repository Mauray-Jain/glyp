package com.mauray.glyp.audio

import android.media.MediaPlayer
import com.mauray.glyp.studio.Clock
import java.io.File

/** [Clock] adapter for the song file; blocks while preparing, so create it off the main thread. */
class MediaPlayerClock(file: File) : Clock {

    private val player = MediaPlayer().apply {
        setDataSource(file.absolutePath)
        prepare()
    }

    override val nowNanos: Long get() = System.nanoTime()
    override val songMs: Long get() = player.currentPosition.toLong()
    override val playing: Boolean get() = player.isPlaying

    override fun play(fromMs: Long) {
        player.seekTo(fromMs, MediaPlayer.SEEK_CLOSEST)
        player.start()
    }

    override fun pause() {
        if (player.isPlaying) player.pause()
    }

    // Created without a Looper of its own, MediaPlayer delivers this on the main thread.
    override fun setOnEnd(listener: () -> Unit) = player.setOnCompletionListener { listener() }

    override fun release() = player.release()
}
