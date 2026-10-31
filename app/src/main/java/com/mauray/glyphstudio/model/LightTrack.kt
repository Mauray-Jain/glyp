package com.mauray.glyphstudio.model

import com.mauray.glyphstudio.glyph.GlyphZones
import java.io.DataInputStream
import java.io.DataOutputStream

/** Read-only view of light data: what the timeline, the Glyphs and export get to see. */
interface LightFrames {
    val frameCount: Int

    /** Stored value (0..[GlyphZones.MAX_BRIGHTNESS]) of [zone] at [frame]. */
    operator fun get(frame: Int, zone: Int): Int

    /** Copies the [GlyphZones.COUNT] stored values of [frame] into [out]. */
    fun copyFrame(frame: Int, out: ShortArray)
}

/**
 * Light data of a composition: [frameCount] frames at 60 fps, each with one brightness
 * value (0..4095) per zone, stored row-major in a flat array so a copy is one memcpy.
 *
 * A plain mutable value: [LightDocument] mutates the one it owns, and everyone else works on
 * copies (snapshots).
 */
class LightTrack private constructor(override val frameCount: Int, private val data: ShortArray) : LightFrames {

    constructor(frameCount: Int) : this(frameCount, ShortArray(frameCount * GlyphZones.COUNT))

    /** Memory one copy of this track takes, for the undo budget. */
    val sizeBytes: Long get() = data.size * 2L

    override operator fun get(frame: Int, zone: Int): Int = data[frame * GlyphZones.COUNT + zone].toInt()

    operator fun set(frame: Int, zone: Int, value: Int) {
        data[frame * GlyphZones.COUNT + zone] = value.coerceIn(0, GlyphZones.MAX_BRIGHTNESS).toShort()
    }

    override fun copyFrame(frame: Int, out: ShortArray) {
        data.copyInto(out, 0, frame * GlyphZones.COUNT, (frame + 1) * GlyphZones.COUNT)
    }

    fun copy() = LightTrack(frameCount, data.copyOf())

    /** Frame count, then every value as a big-endian short. */
    fun writeTo(out: DataOutputStream) {
        out.writeInt(frameCount)
        for (v in data) out.writeShort(v.toInt())
    }

    companion object {
        fun readFrom(input: DataInputStream): LightTrack {
            val frames = input.readInt()
            val data = ShortArray(frames * GlyphZones.COUNT)
            for (i in data.indices) data[i] = input.readShort()
            return LightTrack(frames, data)
        }
    }
}
