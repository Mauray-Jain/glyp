package com.mauray.glyphstudio.studio

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlin.math.min

/** A song clock on the test scheduler's virtual time: advancing time plays the song. */
@OptIn(ExperimentalCoroutinesApi::class)
class FakeClock(private val scope: CoroutineScope, private val scheduler: TestCoroutineScheduler, private val durationMs: Long) : Clock {
    private var startedAt = 0L
    private var from = 0L
    private var end: Job? = null
    private var onEnd: () -> Unit = {}
    var released = false
        private set

    override val nowNanos: Long get() = scheduler.currentTime * 1_000_000
    override var playing = false
        private set
    override val songMs: Long
        get() = if (playing) min(durationMs, from + scheduler.currentTime - startedAt) else from

    override fun play(fromMs: Long) {
        end?.cancel()
        from = fromMs
        startedAt = scheduler.currentTime
        playing = true
        end = scope.launch {
            delay(durationMs - fromMs)
            from = durationMs
            playing = false
            onEnd()
        }
    }

    override fun pause() {
        from = songMs
        playing = false
        end?.cancel()
    }

    override fun setOnEnd(listener: () -> Unit) {
        onEnd = listener
    }

    override fun release() {
        released = true
    }
}
