package com.mauray.glyphstudio.studio

import com.mauray.glyphstudio.glyph.GlyphZones
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The shapes every light writer shares, so a level or a sweep means the same thing whether it
 * came from the composer, an edit tool or a pad.
 */
object LightShapes {

    /** Gamma of the level curve, so mid levels read as clearly dimmer than peaks on the Glyphs. */
    private const val GAMMA = 1.6f

    /** The level curve: a 0..1 level to a stored value in 0..[GlyphZones.MAX_BRIGHTNESS]. */
    fun stored(level: Float): Int = (level.coerceIn(0f, 1f).pow(GAMMA) * GlyphZones.MAX_BRIGHTNESS).roundToInt()

    /**
     * A light trail travelling along [zones]: full [strength] at [head] (a position in zones,
     * 0 = the first one), fading to nothing [tail] zones behind it. Max-blended into [out],
     * which is indexed by zone.
     */
    fun trail(zones: List<Int>, head: Float, tail: Float, strength: Float, out: FloatArray) {
        for ((i, zone) in zones.withIndex()) {
            val behind = head - i
            if (behind in 0f..tail) out[zone] = max(out[zone], strength * (1f - behind / tail))
        }
    }
}
