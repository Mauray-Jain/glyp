package com.mauray.glyphstudio.studio

import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.glyph.GlyphZones.Strip
import kotlin.math.max

/**
 * The five Record-mode pads, laid out like the pad grid in Nothing's Glyph Composer.
 * Hold pads light their zones while held and fade on release; [SWEEP] fires a light trail
 * along the top arc on each press.
 */
enum class Pad(val label: String, val zones: List<Int>) {
    TOP("Top", Strip.C.zones.toList()),
    BOTTOM("Bottom", Strip.B.zones.toList()),
    ALL("All", GlyphZones.ALL.toList()),
    RIGHT("Right", Strip.A.zones.toList()),
    SWEEP("Sweep", Strip.C.zones.toList()),
}

/**
 * Turns pad presses into per-zone light levels over time. Frames are on whatever clock the
 * caller uses (song position while recording, wall time while jamming), so the same engine
 * drives both live preview and recording.
 */
class PadPerformance(
    /** How long a released pad takes to fade out. */
    private val fadeFrames: Int = 9,
    /** How long a sweep takes to cross the arc. */
    private val sweepFrames: Int = 24,
) {
    private class Hit(val pad: Pad, val down: Int, var up: Int? = null)

    private val hits = mutableListOf<Hit>()

    val heldPads: Set<Pad> get() = hits.filter { it.up == null }.map { it.pad }.toSet()

    fun press(pad: Pad, frame: Int) {
        release(pad, frame)
        hits += Hit(pad, frame)
    }

    fun release(pad: Pad, frame: Int) {
        hits.filter { it.pad == pad && it.up == null }.forEach { it.up = max(frame, it.down) }
    }

    fun releaseAll(frame: Int) = Pad.entries.forEach { release(it, frame) }

    fun clear() = hits.clear()

    /** True once nothing is held and every fade or sweep has finished by [frame]. */
    fun isIdle(frame: Int): Boolean {
        hits.removeAll { it.endFrame() < frame }
        return hits.isEmpty()
    }

    /** Writes 0..1 levels for [frame] into [out] (size [GlyphZones.COUNT]), taking the max of all hits. */
    fun levelsAt(frame: Int, out: FloatArray) {
        out.fill(0f)
        for (hit in hits) {
            if (frame < hit.down) continue
            if (hit.pad == Pad.SWEEP) {
                sweep(hit, frame, out)
                continue
            }
            val up = hit.up
            val level = when {
                up == null || frame <= up -> 1f
                frame <= up + fadeFrames -> 1f - (frame - up).toFloat() / fadeFrames
                else -> 0f
            }
            if (level > 0f) for (z in hit.pad.zones) out[z] = max(out[z], level)
        }
    }

    private fun sweep(hit: Hit, frame: Int, out: FloatArray) {
        val zones = hit.pad.zones
        val tail = 5f
        val head = (frame - hit.down).toFloat() / sweepFrames * (zones.size + tail)
        for ((i, zone) in zones.withIndex()) {
            val behind = head - i
            if (behind in 0f..tail) out[zone] = max(out[zone], 1f - behind / tail)
        }
    }

    private fun Hit.endFrame(): Int = when {
        pad == Pad.SWEEP -> down + sweepFrames + 1
        up == null -> Int.MAX_VALUE
        else -> up!! + fadeFrames
    }
}
