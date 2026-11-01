package com.mauray.glyp.studio

import com.mauray.glyp.glyph.GlyphZones
import com.mauray.glyp.model.LightTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LightShapesTest {

    private val top = GlyphZones.Strip.C.zones.toList()

    @Test
    fun `level curve spans the stored range and is perceptual`() {
        assertEquals(0, LightShapes.stored(0f))
        assertEquals(GlyphZones.MAX_BRIGHTNESS, LightShapes.stored(1f))
        assertEquals(1351, LightShapes.stored(0.5f))
        assertEquals("clamped", GlyphZones.MAX_BRIGHTNESS, LightShapes.stored(2f))
        assertEquals("clamped", 0, LightShapes.stored(-1f))
        val curve = (0..100).map { LightShapes.stored(it / 100f) }
        assertEquals("monotonic", curve, curve.sorted())
    }

    @Test
    fun `trail is full at the head and fades over the tail`() {
        val out = FloatArray(GlyphZones.COUNT)
        LightShapes.trail(top, head = 10f, tail = 4f, strength = 1f, out)
        assertEquals(1f, out[top[10]])
        assertEquals(0.75f, out[top[9]], 1e-6f)
        assertEquals(0.25f, out[top[7]], 1e-6f)
        assertEquals("ahead of the head stays dark", 0f, out[top[11]])
        assertEquals("beyond the tail stays dark", 0f, out[top[5]])
    }

    @Test
    fun `trails blend with what is already lit`() {
        val out = FloatArray(GlyphZones.COUNT)
        out[top[3]] = 0.9f
        LightShapes.trail(top, head = 3f, tail = 2f, strength = 0.5f, out)
        assertEquals(0.9f, out[top[3]])
        assertEquals(0.25f, out[top[2]], 1e-6f)
    }

    @Test
    fun `edit sweep, pad sweep and fill all go through the same shapes`() {
        val track = LightTrack(61)
        EditOps.apply(track, EditTool.SWEEP, 0..60, top, brightness = 1f)
        val headAt = { f: Int -> top.indices.maxBy { track[f, top[it]] } }
        assertTrue("edit sweep moves forward", headAt(50) > headAt(10))
        EditOps.apply(track, EditTool.FILL, 0..60, top, brightness = 0.5f)
        assertEquals(LightShapes.stored(0.5f), track[30, top[0]])

        val pads = PadPerformance(sweepFrames = 24)
        val out = FloatArray(GlyphZones.COUNT)
        pads.press(Pad.SWEEP, 0, level = 1f)
        pads.levelsAt(12, out)
        val expected = FloatArray(GlyphZones.COUNT)
        LightShapes.trail(top, 12f / 24 * (top.size + 5f), 5f, 1f, expected)
        for (z in top) assertEquals(expected[z], out[z], 1e-6f)
    }
}
