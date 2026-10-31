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
        val length = (range.last - range.first).coerceAtLeast(1)
        val backwards = zones.reversed()
        // A short tail relative to the selection, so a sweep reads as a moving point.
        val tail = max(2f, zones.size / 4f)
        val levels = FloatArray(GlyphZones.COUNT)

        for (t in range) {
            val progress = (t - range.first).toFloat() / length
            levels.fill(0f)
            when (tool) {
                EditTool.FILL -> zones.forEach { levels[it] = 1f }
                EditTool.CLEAR -> Unit
                EditTool.FADE_IN -> zones.forEach { levels[it] = progress }
                EditTool.FADE_OUT -> zones.forEach { levels[it] = 1 - progress }
                EditTool.PULSE -> {
                    val seconds = (t - range.first).toFloat() / GlyphZones.FPS
                    val level = 0.5f - 0.5f * cos(2 * PI * pulsesPerSecond * seconds).toFloat()
                    zones.forEach { levels[it] = level }
                }
                EditTool.SWEEP -> LightShapes.trail(zones, progress * (zones.size - 1 + tail), tail, 1f, levels)
                EditTool.SWEEP_BACK -> LightShapes.trail(backwards, progress * (zones.size - 1 + tail), tail, 1f, levels)
            }
            for (z in zones) track[t, z] = LightShapes.stored(levels[z] * brightness)
        }
    }
}
