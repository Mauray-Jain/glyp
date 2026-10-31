package com.mauray.glyphstudio.studio

import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.model.LightDocument
import com.mauray.glyphstudio.model.frameToMs
import com.mauray.glyphstudio.model.msToFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Everything that happens while the song or the pads are live: playback on the [clock], the
 * pad performance, the take being recorded, and the frame shown on screen and on the Glyphs
 * (the track at the playhead with the pads on top).
 *
 * One render tick runs while the song plays or any hit is still lit. Pad hits are stamped on
 * the clock's monotonic time, so they survive play and pause; while recording, each tick maps
 * them onto the song frames that played since the last tick and writes them into the take.
 *
 * A take is one [LightDocument] transaction. It is committed by [stop], [pause], the end of
 * the song and [close], so every way out of recording keeps what was played.
 */
class TakeRecorder(
    private val document: LightDocument,
    private val clock: Clock,
    private val scope: CoroutineScope,
    /** Receives every frame the Glyphs should show. */
    private val output: (ShortArray) -> Unit,
) {
    /** What the Studio shows: [lights] are the stored values on the phone right now. */
    data class Live(
        val playhead: Int,
        val playing: Boolean,
        val recording: Boolean,
        val heldPads: Set<Pad>,
        val lights: IntArray,
    )

    private class Take(val transaction: LightDocument.Transaction, val overdub: Boolean, var written: Int) {
        var published = written
    }

    private val lastFrame = document.frameCount - 1
    private val origin = clock.nowNanos
    private val performance = PadPerformance()
    private val levels = FloatArray(GlyphZones.COUNT)
    private val frame = ShortArray(GlyphZones.COUNT)
    private var playhead = 0
    private var take: Take? = null
    private var tick: Job? = null

    private val _live = MutableStateFlow(Live(0, playing = false, recording = false, emptySet(), IntArray(GlyphZones.COUNT)))
    val live: StateFlow<Live> = _live.asStateFlow()

    init {
        clock.setOnEnd(::onEnd)
        // Edits change what the playhead shows; while ticking the next tick picks them up.
        scope.launch { document.revision.collect { if (tick?.isActive != true) render() } }
    }

    /** Plays from the playhead, rewinding first if it is at the end. */
    fun play() {
        if (clock.playing) return
        if (playhead >= lastFrame) playhead = 0
        clock.play(frameToMs(playhead))
        startTicking()
    }

    /** Pauses playback; a running take is committed. Held pads keep glowing. */
    fun pause() {
        finishTake()
        clock.pause()
        render()
    }

    /** Ignored while recording, so a take is always one continuous stretch of the song. */
    fun seek(frame: Int) {
        if (take != null) return
        playhead = frame.coerceIn(0, lastFrame)
        if (clock.playing) clock.play(frameToMs(playhead))
        render()
    }

    /**
     * Starts a take from the playhead (rewinding at the end) and plays. With [overdub] pad hits
     * are layered over the existing lights; otherwise they replace them as the song plays.
     */
    fun record(overdub: Boolean) {
        if (take != null) return
        if (playhead >= lastFrame) playhead = 0
        take = Take(document.begin(), overdub, written = playhead - 1)
        clock.play(frameToMs(playhead))
        startTicking()
    }

    fun stop() = pause()

    /** [level] is the pad brightness, 0..1. */
    fun pressPad(pad: Pad, level: Float) {
        performance.press(pad, nowFrame(), level)
        startTicking()
        render()
    }

    fun releasePad(pad: Pad) {
        performance.release(pad, nowFrame())
        render()
    }

    /** Commits any take and lets go of the clock. */
    fun close() {
        pause()
        tick?.cancel()
        clock.release()
    }

    private fun onEnd() {
        playhead = msToFrame(clock.songMs).coerceIn(0, lastFrame)
        finishTake()
        render()
    }

    private fun startTicking() {
        if (tick?.isActive == true) return
        tick = scope.launch {
            do {
                advance()
                render()
                delay(TICK_MS)
            } while (clock.playing || !performance.isIdle(nowFrame()))
            render()
        }
    }

    /** Moves the playhead with the song and writes the take up to it. */
    private fun advance() {
        val now = nowFrame()
        if (clock.playing) playhead = msToFrame(clock.songMs).coerceIn(0, lastFrame)
        take?.let { write(it, now) }
        performance.forgetEndedBefore(now)
    }

    private fun write(t: Take, now: Int) {
        if (playhead <= t.written) return
        // Pad time that lines up with song frame f; measured each tick so drift can't build up.
        val offset = now - playhead
        t.transaction.update { track ->
            for (f in t.written + 1..playhead) {
                performance.levelsAt(f + offset, levels)
                for (z in 0 until GlyphZones.COUNT) {
                    val v = stored(levels[z])
                    track[f, z] = if (t.overdub) max(track[f, z], v) else v
                }
            }
        }
        t.written = playhead
        // Let the timeline catch up ~10x a second rather than on every tick.
        if (playhead - t.published >= PUBLISH_FRAMES) {
            t.transaction.publish()
            t.published = playhead
        }
    }

    private fun finishTake() {
        val t = take ?: return
        advance()
        t.transaction.commit()
        take = null
    }

    private fun render() {
        performance.levelsAt(nowFrame(), levels)
        document.copyFrame(playhead, frame)
        val lights = IntArray(GlyphZones.COUNT) { z ->
            max(frame[z].toInt(), stored(levels[z])).also { frame[z] = it.toShort() }
        }
        output(frame)
        _live.value = Live(playhead, clock.playing, take != null, performance.heldPads, lights)
    }

    private fun stored(level: Float) = (level * GlyphZones.MAX_BRIGHTNESS).toInt()

    private fun nowFrame(): Int = ((clock.nowNanos - origin) * GlyphZones.FPS / 1_000_000_000L).toInt()

    private companion object {
        const val TICK_MS = 8L
        const val PUBLISH_FRAMES = 6
    }
}
