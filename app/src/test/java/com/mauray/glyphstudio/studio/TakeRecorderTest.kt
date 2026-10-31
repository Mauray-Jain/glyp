package com.mauray.glyphstudio.studio

import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.model.LightDocument
import com.mauray.glyphstudio.model.LightTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TakeRecorderTest {

    private val topZone = GlyphZones.Strip.C.zones.first
    private val rightZone = GlyphZones.Strip.A.zones.first

    private class Rig(val doc: LightDocument, val clock: FakeClock, val recorder: TakeRecorder, val shown: MutableList<ShortArray>)

    /** 10 s song (600 frames). */
    private fun TestScope.rig(prefill: (LightTrack) -> Unit = {}): Rig {
        val doc = LightDocument(LightTrack(600).also(prefill))
        val clock = FakeClock(backgroundScope, testScheduler, durationMs = 10_000)
        val shown = mutableListOf<ShortArray>()
        val recorder = TakeRecorder(doc, clock, backgroundScope) { shown += it.copyOf() }
        return Rig(doc, clock, recorder, shown)
    }

    private fun TestScope.advance(ms: Long) = testScheduler.advanceTimeBy(ms).also { testScheduler.runCurrent() }

    @Test
    fun `playhead follows the song and pause stops it`() = runTest {
        val r = rig()
        r.recorder.play()
        advance(1_000)
        val at = r.recorder.live.value.playhead
        assertTrue("near frame 60, was $at", at in 58..60)
        r.recorder.pause()
        advance(1_000)
        assertEquals(at, r.recorder.live.value.playhead)
        assertFalse(r.recorder.live.value.playing)
    }

    @Test
    fun `take writes hits at the song frames they were played on`() = runTest {
        val r = rig()
        r.recorder.seek(120)
        r.recorder.record(overdub = true)
        advance(500) // song at 2.5 s, frame 150
        r.recorder.pressPad(Pad.RIGHT, 1f)
        advance(500) // frame 180
        r.recorder.releasePad(Pad.RIGHT)
        advance(500)
        r.recorder.stop()
        assertFalse(r.recorder.live.value.recording)
        assertEquals(0, r.doc[140, rightZone])
        assertEquals(GlyphZones.MAX_BRIGHTNESS, r.doc[165, rightZone])
        assertEquals("other strips stay dark", 0, r.doc[165, topZone])
        assertEquals("faded out after release", 0, r.doc[200, rightZone])
    }

    @Test
    fun `a take is one undo step`() = runTest {
        val r = rig()
        r.recorder.record(overdub = true)
        r.recorder.pressPad(Pad.ALL, 1f)
        advance(1_000)
        r.recorder.stop()
        assertTrue(r.doc[30, topZone] > 0)
        r.doc.undo()
        assertEquals(0, r.doc[30, topZone])
        assertFalse(r.doc.canUndo)
    }

    @Test
    fun `end of song commits the take up to the last frame`() = runTest {
        val r = rig()
        r.recorder.seek(540)
        r.recorder.record(overdub = true)
        r.recorder.pressPad(Pad.TOP, 1f)
        advance(2_000)
        val live = r.recorder.live.value
        assertFalse(live.recording)
        assertFalse(live.playing)
        assertTrue(r.doc.canUndo)
        assertEquals(GlyphZones.MAX_BRIGHTNESS, r.doc[599, topZone])
    }

    @Test
    fun `replace overwrites existing lights where no pad is held`() = runTest {
        val r = rig { track -> for (f in 0 until 600) track[f, rightZone] = 1000 }
        r.recorder.record(overdub = false)
        advance(500)
        r.recorder.stop()
        assertEquals(0, r.doc[10, rightZone])
        assertEquals("untouched after the take", 1000, r.doc[200, rightZone])
    }

    @Test
    fun `layer keeps the brighter of track and pads`() = runTest {
        val r = rig { track -> for (f in 0 until 600) track[f, rightZone] = 1000 }
        r.recorder.record(overdub = true)
        r.recorder.pressPad(Pad.TOP, 0.5f)
        advance(500)
        r.recorder.stop()
        assertEquals(1000, r.doc[10, rightZone])
        assertEquals("half brightness through the level curve", LightShapes.stored(0.5f), r.doc[10, topZone])
    }

    @Test
    fun `held pads survive play and pause`() = runTest {
        val r = rig()
        r.recorder.pressPad(Pad.TOP, 1f)
        advance(100)
        r.recorder.play()
        advance(300)
        r.recorder.pause()
        advance(300)
        val live = r.recorder.live.value
        assertEquals(setOf(Pad.TOP), live.heldPads)
        assertEquals(GlyphZones.MAX_BRIGHTNESS, live.lights[topZone])
        assertEquals(GlyphZones.MAX_BRIGHTNESS.toShort(), r.shown.last()[topZone])
    }

    @Test
    fun `jamming lights the glyphs but writes nothing`() = runTest {
        val r = rig()
        r.recorder.pressPad(Pad.RIGHT, 1f)
        advance(200)
        assertEquals(GlyphZones.MAX_BRIGHTNESS, r.recorder.live.value.lights[rightZone])
        r.recorder.releasePad(Pad.RIGHT)
        advance(1_000)
        assertEquals("faded out", 0, r.recorder.live.value.lights[rightZone])
        assertFalse(r.doc.canUndo)
        assertEquals(0, r.doc.revision.value)
    }

    @Test
    fun `seek is ignored while recording`() = runTest {
        val r = rig()
        r.recorder.record(overdub = true)
        advance(200)
        r.recorder.seek(400)
        assertTrue(r.recorder.live.value.playhead < 30)
    }

    @Test
    fun `play from the end rewinds`() = runTest {
        val r = rig()
        r.recorder.seek(599)
        r.recorder.play()
        advance(100)
        assertTrue(r.recorder.live.value.playhead < 10)
    }

    @Test
    fun `the end of the song parks the playhead so play starts over`() = runTest {
        val r = rig()
        r.recorder.seek(590)
        r.recorder.play()
        advance(1_000)
        assertFalse(r.recorder.live.value.playing)
        assertEquals(599, r.recorder.live.value.playhead)
        r.recorder.play()
        advance(100)
        assertTrue(r.recorder.live.value.playing)
        assertTrue(r.recorder.live.value.playhead < 10)
    }

    @Test
    fun `seeking then playing starts on the frame that was picked`() = runTest {
        val r = rig()
        for (frame in listOf(1, 7, 59, 61, 333)) {
            r.recorder.seek(frame)
            r.recorder.play()
            assertEquals(frame, r.recorder.live.value.playhead)
            testScheduler.runCurrent()
            assertEquals(frame, r.recorder.live.value.playhead)
            r.recorder.pause()
        }
    }

    @Test
    fun `edits show at the playhead while idle`() = runTest {
        val r = rig()
        r.recorder.seek(50)
        r.doc.edit { it[50, rightZone] = 777 }
        testScheduler.runCurrent()
        assertEquals(777, r.recorder.live.value.lights[rightZone])
        assertEquals(777.toShort(), r.shown.last()[rightZone])
    }

    @Test
    fun `close commits the take and releases the clock`() = runTest {
        val r = rig()
        r.recorder.record(overdub = true)
        r.recorder.pressPad(Pad.ALL, 1f)
        advance(300)
        r.recorder.close()
        assertTrue(r.clock.released)
        assertTrue(r.doc.canUndo)
        assertTrue(r.doc[10, topZone] > 0)
    }
}
