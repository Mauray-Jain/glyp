package com.mauray.glyphstudio.audio

import com.mauray.glyphstudio.composer.Analysis
import com.mauray.glyphstudio.composer.AudioAnalyzer
import com.mauray.glyphstudio.glyph.GlyphZones
import java.io.File
import kotlin.math.abs
import kotlin.math.max

/**
 * The one mapping between light frames and song time. Frame f covers
 * [msAt(f), msAt(f + 1)); [msAt] rounds up so that `frameAt(msAt(f)) == f` for every frame.
 */
object Timebase {
    /** First whole millisecond inside [frame]. */
    fun msAt(frame: Int): Long = (frame * 1000L + GlyphZones.FPS - 1) / GlyphZones.FPS

    /** The frame playing at [ms]. */
    fun frameAt(ms: Long): Int = (ms * GlyphZones.FPS / 1000).toInt()

    /** Frames needed to cover [durationMs] of song. */
    fun framesFor(durationMs: Long): Int = ((durationMs * GlyphZones.FPS + 999) / 1000).toInt()
}

/**
 * A project's audio, decoded once: the PCM (reused by export), the per-frame [analysis] the
 * composer works from, and the [waveform] the timeline draws, all [frameCount] frames long.
 */
class Song private constructor(
    val pcm: PcmAudio,
    val frameCount: Int,
    val analysis: Analysis,
    /** Peak amplitude per light frame, 0..1 (the loudest frame is 1). */
    val waveform: FloatArray,
) {
    companion object {
        /** Decodes [file] (any platform format) and analyses it; Android only. */
        fun decode(file: File, frameCount: Int, onProgress: (Float) -> Unit = {}): Song =
            of(AudioDecoder.decode(file) { onProgress(it * 0.4f) }, frameCount) { onProgress(0.4f + it * 0.6f) }

        /** Analyses already decoded audio; pure JVM. */
        fun of(pcm: PcmAudio, frameCount: Int, onProgress: (Float) -> Unit = {}): Song {
            val mono = pcm.toMono()
            val analysis = AudioAnalyzer.analyze(mono, pcm.sampleRate, frameCount, onProgress)
            return Song(pcm, frameCount, analysis, waveform(mono, pcm.sampleRate, frameCount))
        }

        private fun waveform(mono: FloatArray, sampleRate: Int, frames: Int): FloatArray {
            val out = FloatArray(frames)
            val perFrame = sampleRate.toDouble() / GlyphZones.FPS
            for (t in 0 until frames) {
                val from = (t * perFrame).toInt()
                val to = minOf(mono.size, ((t + 1) * perFrame).toInt())
                var peak = 0f
                for (i in from until to) peak = max(peak, abs(mono[i]))
                out[t] = peak
            }
            val ref = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
            for (i in out.indices) out[i] /= ref
            return out
        }
    }
}
