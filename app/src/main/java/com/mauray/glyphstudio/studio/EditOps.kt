package com.mauray.glyphstudio.studio

import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.model.LightTrack
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max

/** Hand-editing tools of the Studio. Each one paints [zones] over [frames]. */
enum class EditTool(val label: String) {
    FILL("Fill"),
    FADE_IN("Fade in"),
    FADE_OUT("Fade out"),
    PULSE("Pulse"),
    SWEEP("Sweep"),
    SWEEP_BACK("Sweep back"),
    CLEAR("Clear"),
}

object EditOps {

    /**
     * @param brightness 0..1 peak level
     * @param pulsesPerSecond only used by [EditTool.PULSE]
     * @param zones in the order a sweep should travel
     */
    fun apply(
        track: LightTrack,
        tool: EditTool,
        frames: IntRange,
        zones: List<Int>,
        brightness: Float,
        pulsesPerSecond: Float = 2f,
    ) {
        val range = max(0, frames.first)..minOf(track.frameCount - 1, frames.last)
        if (range.isEmpty() || zones.isEmpty()) return
        val peak = brightness.coerceIn(0f, 1f) * GlyphZones.MAX_BRIGHTNESS
        val length = (range.last - range.first).coerceAtLeast(1)

        for (t in range) {
            val progress = (t - range.first).toFloat() / length
            when (tool) {
                EditTool.FILL -> zones.forEach { track[t, it] = peak.toInt() }
                EditTool.CLEAR -> zones.forEach { track[t, it] = 0 }
                EditTool.FADE_IN -> zones.forEach { track[t, it] = (peak * progress).toInt() }
                EditTool.FADE_OUT -> zones.forEach { track[t, it] = (peak * (1 - progress)).toInt() }
                EditTool.PULSE -> {
                    val seconds = (t - range.first).toFloat() / GlyphZones.FPS
                    val level = 0.5f - 0.5f * cos(2 * PI * pulsesPerSecond * seconds).toFloat()
                    zones.forEach { track[t, it] = (peak * level).toInt() }
                }
                EditTool.SWEEP, EditTool.SWEEP_BACK -> {
                    val order = if (tool == EditTool.SWEEP) zones else zones.reversed()
                    sweep(track, t, order, progress, peak)
                }
            }
        }
    }

    /** A bright head with a short fading tail moving through [order] as [progress] goes 0→1. */
    private fun sweep(track: LightTrack, t: Int, order: List<Int>, progress: Float, peak: Float) {
        val tail = max(2f, order.size / 4f)
        val head = progress * (order.size - 1 + tail)
        for ((i, zone) in order.withIndex()) {
            val behind = head - i
            val level = if (behind in 0f..tail) 1f - behind / tail else 0f
            track[t, zone] = (peak * level).toInt()
        }
    }
}
