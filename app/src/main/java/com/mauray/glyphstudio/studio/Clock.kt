package com.mauray.glyphstudio.studio

/**
 * Where the take gets its time from: the song's position, and a monotonic clock for pad hits
 * (which keep running while the song is paused). MediaPlayer on the phone, a fake in tests.
 */
interface Clock {
    /** Monotonic time; only differences matter. */
    val nowNanos: Long

    /** Song position. */
    val songMs: Long

    val playing: Boolean

    /** Seeks to [fromMs] and plays (also when already playing). */
    fun play(fromMs: Long)

    fun pause()

    /** [listener] runs, on the thread that owns the clock, each time the song plays to its end. */
    fun setOnEnd(listener: () -> Unit)

    fun release()
}
