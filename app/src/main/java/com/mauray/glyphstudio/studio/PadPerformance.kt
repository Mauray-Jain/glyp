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
 * Turns pad presses into per-zone light levels over time. Frames are on the caller's
 * monotonic clock; the take maps them onto song frames while recording.
 */
class PadPerformance(
    /** How long a released pad takes to fade out. */
    private val fadeFrames: Int = 9,
    /** How long a sweep takes to cross the arc. */
    private val sweepFrames: Int = 24,
) {
    /** [level] is the pad brightness (0..1) at the time of the press. */
    private class Hit(val pad: Pad, val down: Int, val level: Float, var up: Int? = null)

    private val hits = mutableListOf<Hit>()

    val heldPads: Set<Pad> get() = hits.filter { it.up == null }.map { it.pad }.toSet()

    fun press(pad: Pad, frame: Int, level: Float = 1f) {
        release(pad, frame)
        hits += Hit(pad, frame, level.coerceIn(0f, 1f))
    }

    fun release(pad: Pad, frame: Int) {
        hits.filter { it.pad == pad && it.up == null }.forEach { it.up = max(frame, it.down) }
    }

    /** True once nothing is held and every fade or sweep has finished by [frame]. */
    fun isIdle(frame: Int): Boolean = hits.all { it.endFrame() < frame }

    /** Drops hits that have finished before [frame]; levels before it are no longer needed. */
    fun forgetEndedBefore(frame: Int) {
        hits.removeAll { it.endFrame() < frame }
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
            val shape = when {
                up == null || frame <= up -> 1f
                frame <= up + fadeFrames -> 1f - (frame - up).toFloat() / fadeFrames
                else -> 0f
            }
            val level = shape * hit.level
            if (level > 0f) for (z in hit.pad.zones) out[z] = max(out[z], level)
        }
    }

    /** The trail runs off the far end of the arc in [sweepFrames]. */
    private fun sweep(hit: Hit, frame: Int, out: FloatArray) {
        val zones = hit.pad.zones
        val head = (frame - hit.down).toFloat() / sweepFrames * (zones.size + SWEEP_TAIL)
        LightShapes.trail(zones, head, SWEEP_TAIL, hit.level, out)
    }

    private fun Hit.endFrame(): Int = when {
        pad == Pad.SWEEP -> down + sweepFrames + 1
        else -> up?.let { it + fadeFrames } ?: Int.MAX_VALUE
    }

    private companion object {
        const val SWEEP_TAIL = 5f
    }
}
