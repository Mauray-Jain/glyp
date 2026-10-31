package com.mauray.glyphstudio.studio

import com.mauray.glyphstudio.glyph.GlyphZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadPerformanceTest {

    private val out = FloatArray(GlyphZones.COUNT)

    @Test
    fun `held pad lights only its strip`() {
        val p = PadPerformance()
        p.press(Pad.RIGHT, 10)
        p.levelsAt(15, out)
        for (z in GlyphZones.ALL) assertEquals("zone $z", if (z in GlyphZones.Strip.A.zones) 1f else 0f, out[z])
    }

    @Test
    fun `release fades out then goes idle`() {
        val p = PadPerformance(fadeFrames = 10)
        p.press(Pad.TOP, 0)
        p.release(Pad.TOP, 20)
        p.levelsAt(25, out)
        assertEquals(0.5f, out[0], 1e-4f)
        p.levelsAt(31, out)
        assertEquals(0f, out[0])
        assertFalse(p.isIdle(29))
        assertTrue(p.isIdle(31))
    }

    @Test
    fun `nothing before the press`() {
        val p = PadPerformance()
        p.press(Pad.ALL, 50)
        p.levelsAt(49, out)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun `sweep travels from first to last zone of the top arc`() {
        val p = PadPerformance(sweepFrames = 24)
        p.press(Pad.SWEEP, 0)
        p.levelsAt(2, out)
        val early = GlyphZones.Strip.C.zones.maxBy { out[it] }
        p.levelsAt(18, out)
        val late = GlyphZones.Strip.C.zones.maxBy { out[it] }
        assertTrue("head moves forward: $early -> $late", late > early)
        assertTrue(p.isIdle(30))
    }

    @Test
    fun `held pads are reported until released`() {
        val p = PadPerformance()
        p.press(Pad.TOP, 0)
        p.press(Pad.BOTTOM, 1)
        assertEquals(setOf(Pad.TOP, Pad.BOTTOM), p.heldPads)
        p.release(Pad.TOP, 5)
        p.release(Pad.BOTTOM, 5)
        assertTrue(p.heldPads.isEmpty())
    }

    @Test
    fun `hits keep the brightness they were played at`() {
        val p = PadPerformance()
        p.press(Pad.RIGHT, 0, level = 0.25f)
        p.levelsAt(1, out)
        assertEquals(0.25f, out[GlyphZones.Strip.A.zones.first], 1e-6f)
    }

    @Test
    fun `asking whether idle does not forget hits`() {
        val p = PadPerformance(fadeFrames = 2)
        p.press(Pad.TOP, 0)
        p.release(Pad.TOP, 1)
        assertTrue(p.isIdle(10))
        p.levelsAt(2, out)
        assertTrue("fade still readable after isIdle", out[0] > 0f)
        p.forgetEndedBefore(10)
        p.levelsAt(2, out)
        assertEquals(0f, out[0])
    }
}
