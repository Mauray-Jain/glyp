package com.mauray.glyphstudio.model

import com.mauray.glyphstudio.glyph.GlyphZones

/**
 * Light data of a composition: [frameCount] frames at 60 fps, each with one brightness
 * value (0..4095) per zone, stored row-major in a flat [ShortArray] to keep undo cheap.
 */
class LightTrack(val frameCount: Int, val data: ShortArray = ShortArray(frameCount * GlyphZones.COUNT)) {

    init {
        require(data.size == frameCount * GlyphZones.COUNT)
    }

    operator fun get(frame: Int, zone: Int): Int = data[frame * GlyphZones.COUNT + zone].toInt()

    operator fun set(frame: Int, zone: Int, value: Int) {
        data[frame * GlyphZones.COUNT + zone] = value.coerceIn(0, GlyphZones.MAX_BRIGHTNESS).toShort()
    }

    fun offsetOf(frame: Int) = frame * GlyphZones.COUNT

    fun copy() = LightTrack(frameCount, data.copyOf())

    companion object {
        fun framesForDuration(durationMs: Long) = ((durationMs * GlyphZones.FPS + 999) / 1000).toInt()
    }
}

/** Frame index → milliseconds and back. */
fun frameToMs(frame: Int): Long = frame * 1000L / GlyphZones.FPS
fun msToFrame(ms: Long): Int = (ms * GlyphZones.FPS / 1000).toInt()
