package com.mauray.glyp.composer

import com.mauray.glyp.glyph.GlyphZones
import com.mauray.glyp.glyph.GlyphZones.Strip
import com.mauray.glyp.model.LightTrack
import com.mauray.glyp.studio.LightShapes
import kotlin.math.max
import kotlin.math.min

enum class ComposerStyle(val label: String, val description: String) {
    PULSE("Pulse", "Kicks, snares and hats each get a strip"),
    SPECTRUM("Spectrum", "The top arc becomes an equaliser"),
    FLOW("Flow", "Light trails race the arc on every hit"),
    CLASSIC("Classic", "Sparse flashes, like stock ringtones"),
}

data class ComposerParams(
    val style: ComposerStyle = ComposerStyle.PULSE,
    /** 0..1, higher reacts to quieter hits. */
    val sensitivity: Float = 0.5f,
    /** How long a flash takes to fade out. */
    val releaseMs: Float = 250f,
    /** 0..1 peak brightness. */
    val intensity: Float = 1f,
)

/**
 * Turns an [Analysis] into light data. Each style writes every zone of every frame in the
 * given range, so it can regenerate a selection without touching the rest of the track.
 */
object AutoComposer {

    fun compose(analysis: Analysis, params: ComposerParams, track: LightTrack, range: IntRange = 0 until track.frameCount) {
        val frames = min(analysis.frameCount, track.frameCount)
        val clipped = max(0, range.first)..min(frames - 1, range.last)
        if (clipped.isEmpty()) return
        val levels = Array(frames) { FloatArray(GlyphZones.COUNT) }
        when (params.style) {
            ComposerStyle.PULSE -> pulse(analysis, params, levels)
            ComposerStyle.SPECTRUM -> spectrum(analysis, params, levels)
            ComposerStyle.FLOW -> flow(analysis, params, levels)
            ComposerStyle.CLASSIC -> classic(analysis, params, levels)
        }
        val intensity = params.intensity.coerceIn(0f, 1f)
        for (t in clipped) {
            for (z in 0 until GlyphZones.COUNT) track[t, z] = LightShapes.stored(levels[t][z].coerceIn(0f, 1f) * intensity)
        }
    }

    private fun pulse(a: Analysis, p: ComposerParams, out: Array<FloatArray>) {
        val bassHits = AudioAnalyzer.onsets(a.bassFlux, p.sensitivity)
        val midHits = AudioAnalyzer.onsets(a.midFlux, p.sensitivity)
        val highHits = AudioAnalyzer.onsets(a.highFlux, p.sensitivity, minGapFrames = 4)
        val decay = AudioAnalyzer.decayFactor(p.releaseMs)
        val fastDecay = AudioAnalyzer.decayFactor(p.releaseMs * 0.5f)
        var levelA = 0f
        var levelC = 0f
        var levelB = 0f
        for (t in out.indices) {
            levelA = if (bassHits[t]) max(levelA, 0.55f + 0.45f * a.bass[t]) else levelA * decay
            levelC = if (midHits[t]) max(levelC, 0.5f + 0.5f * a.mid[t]) else levelC * decay
            levelB = if (highHits[t]) 1f else levelB * fastDecay
            val glow = a.loudness[t] * 0.12f
            fill(out[t], Strip.A, max(levelA, glow))
            fill(out[t], Strip.C, max(levelC, glow))
            fill(out[t], Strip.B, levelB)
        }
    }

    private fun spectrum(a: Analysis, p: ComposerParams, out: Array<FloatArray>) {
        val decay = AudioAnalyzer.decayFactor(p.releaseMs)
        val gain = 0.6f + 0.8f * p.sensitivity
        val held = FloatArray(GlyphZones.COUNT)
        for (t in out.indices) {
            // C1 (bottom-left) = lowest band, C20 (top-right) = highest.
            for ((i, zone) in Strip.C.zones.withIndex()) {
                held[zone] = max(a.band(t, i) * gain, held[zone] * decay)
            }
            // A fills upward from A11 (bottom) to A1, B from B1 (bottom-right) to B5.
            meter(held, Strip.A.zones.reversed().toList(), a.bass[t] * gain, decay)
            meter(held, Strip.B.zones.toList(), a.high[t] * gain, decay)
            held.copyInto(out[t])
        }
    }

    private fun flow(a: Analysis, p: ComposerParams, out: Array<FloatArray>) {
        val hits = combinedOnsets(a, p.sensitivity)
        val highHits = AudioAnalyzer.onsets(a.highFlux, p.sensitivity, minGapFrames = 5)
        val decay = AudioAnalyzer.decayFactor(p.releaseMs)
        val cZones = Strip.C.zones.toList()
        val cBackwards = cZones.reversed()
        val bZones = Strip.B.zones.toList()
        // Each comet crosses strip C in ~0.4 s, alternating direction.
        val speed = cZones.size / (0.4f * GlyphZones.FPS)
        val tail = 5f
        val comets = mutableListOf<Comet>()
        var forward = true
        var bPos = 0
        var bLevel = 0f
        val levelsA = FloatArray(GlyphZones.COUNT)
        for (t in out.indices) {
            if (hits[t]) {
                comets += Comet(t, forward, 0.5f + 0.5f * a.loudness[t])
                forward = !forward
            }
            comets.removeAll { (t - it.start) * speed > cZones.size + tail }
            for (comet in comets) {
                val head = (t - comet.start) * speed
                LightShapes.trail(if (comet.forward) cZones else cBackwards, head, tail, comet.strength, out[t])
            }
            meter(levelsA, Strip.A.zones.reversed().toList(), a.loudness[t], decay)
            for (zone in Strip.A.zones) out[t][zone] = levelsA[zone]
            if (highHits[t]) {
                bPos = (bPos + 1) % bZones.size
                bLevel = 1f
            } else {
                bLevel *= decay
            }
            out[t][bZones[bPos]] = bLevel
        }
    }

    private fun classic(a: Analysis, p: ComposerParams, out: Array<FloatArray>) {
        // Stricter than the other styles: only the clearest hits, at least 150 ms apart.
        val strict = p.sensitivity * 0.6f
        val bassHits = AudioAnalyzer.onsets(a.bassFlux, strict, minGapFrames = 9)
        val midHits = AudioAnalyzer.onsets(a.midFlux, strict, minGapFrames = 9)
        val highHits = AudioAnalyzer.onsets(a.highFlux, strict, minGapFrames = 9)
        val decay = AudioAnalyzer.decayFactor(min(p.releaseMs, 180f))
        val level = FloatArray(Strip.entries.size)
        for (t in out.indices) {
            for (s in Strip.entries) level[s.ordinal] *= decay
            if (bassHits[t]) level[Strip.A.ordinal] = 1f
            if (midHits[t]) level[Strip.C.ordinal] = 1f
            if (highHits[t]) level[Strip.B.ordinal] = 1f
            for (s in Strip.entries) fill(out[t], s, if (level[s.ordinal] > 0.03f) level[s.ordinal] else 0f)
        }
    }

    private class Comet(val start: Int, val forward: Boolean, val strength: Float)

    private fun combinedOnsets(a: Analysis, sensitivity: Float): BooleanArray {
        val combined = FloatArray(a.frameCount) { a.bassFlux[it] * 0.5f + a.midFlux[it] * 0.35f + a.highFlux[it] * 0.15f }
        return AudioAnalyzer.onsets(combined, sensitivity, minGapFrames = 8)
    }

    private fun fill(frame: FloatArray, strip: Strip, level: Float) {
        for (z in strip.zones) frame[z] = level
    }

    /**
     * Lights [zones] in order up to [value] (0..1) with a soft top segment; segments above the
     * new level fall back with [decay] instead of dropping instantly.
     */
    private fun meter(levels: FloatArray, zones: List<Int>, value: Float, decay: Float) {
        val lit = value.coerceIn(0f, 1f) * zones.size
        for ((i, zone) in zones.withIndex()) {
            val target = (lit - i).coerceIn(0f, 1f)
            levels[zone] = if (target >= levels[zone]) target else max(target, levels[zone] * decay)
        }
    }
}
