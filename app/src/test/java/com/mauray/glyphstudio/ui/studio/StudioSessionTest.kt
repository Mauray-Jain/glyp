package com.mauray.glyphstudio.ui.studio

import com.mauray.glyphstudio.composer.AudioAnalyzer
import com.mauray.glyphstudio.composer.ComposerParams
import com.mauray.glyphstudio.export.ExportJob
import com.mauray.glyphstudio.export.Ringtone
import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.model.ProjectStore
import com.mauray.glyphstudio.studio.EditTool
import com.mauray.glyphstudio.studio.FakeClock
import com.mauray.glyphstudio.studio.Pad
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class StudioSessionTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val store by lazy { ProjectStore(tmp.root, Dispatchers.Unconfined) }
    private val rightZone = GlyphZones.Strip.A.zones.first

    private class Rig(val session: StudioSession, val id: String, val messages: List<String>, val exports: List<ExportJob>)

    /** A 10 s project (600 frames) whose song is a 2 Hz click, so composing lights something. */
    private fun TestScope.rig(autoCompose: Boolean = false, exportFails: Boolean = false): Rig {
        val id = kotlinx.coroutines.runBlocking {
            store.create("Song.ogg", 10_000, autoCompose) { "audio".byteInputStream() }.meta.id
        }
        val exports = mutableListOf<ExportJob>()
        val session = StudioSession(
            projectId = id,
            scope = backgroundScope,
            store = store,
            analyze = { _, frames, onProgress ->
                val rate = 8_000
                val mono = FloatArray(rate * 10) { i -> if (i % (rate / 2) < 200) sin(i * 0.3f) else 0f }
                onProgress(0.5f)
                AudioAnalyzer.analyze(mono, rate, frames) to FloatArray(frames)
            },
            openClock = { FakeClock(backgroundScope, testScheduler, durationMs = 10_000) },
            exportRingtone = { job, onProgress ->
                exports += job
                onProgress(0.5f)
                delay(100)
                if (exportFails) error("disk full")
                object : Ringtone {
                    override fun setAsDefault() = true
                }
            },
            showLights = {},
            compute = StandardTestDispatcher(testScheduler),
        )
        val messages = mutableListOf<String>()
        backgroundScope.launch { session.messages.collect { messages += it } }
        testScheduler.runCurrent()
        return Rig(session, id, messages, exports)
    }

    private val Rig.open get() = session.state.value as StudioState.Open

    private fun TestScope.advance(ms: Long) {
        testScheduler.advanceTimeBy(ms)
        testScheduler.runCurrent()
    }

    private fun fill(frames: IntRange) = PaintRequest(EditTool.FILL, frames, setOf(rightZone), 1f, 2f)

    @Test
    fun `opens idle and composes a new project once`() = runTest {
        val r = rig(autoCompose = true)
        assertEquals(Phase.Idle, r.open.phase)
        assertEquals(600, r.open.frameCount)
        assertFalse("composition is the starting point, not an undo step", r.open.canUndo)
        val saved = store.load(r.id)
        assertFalse(saved.meta.pendingAutoCompose)
        assertTrue("composed lights were saved", (0 until 600).any { f -> GlyphZones.ALL.any { saved.lights[f, it] > 0 } })
    }

    @Test
    fun `a missing project fails with a message`() = runTest {
        val session = StudioSession(
            "nope", backgroundScope, store,
            analyze = { _, _, _ -> error("unused") },
            openClock = { error("unused") },
            exportRingtone = { _, _ -> error("unused") },
            showLights = {},
            compute = StandardTestDispatcher(testScheduler),
        )
        testScheduler.runCurrent()
        assertTrue(session.state.value is StudioState.Failed)
    }

    @Test
    fun `phase follows play, record and stop`() = runTest {
        val r = rig()
        r.session.togglePlay()
        advance(100)
        assertEquals(Phase.Playing, r.open.phase)
        r.session.toggleRecord(overdub = true)
        advance(100)
        assertEquals(Phase.Recording, r.open.phase)
        r.session.togglePlay()
        testScheduler.runCurrent()
        assertEquals(Phase.Idle, r.open.phase)
        assertTrue("the take was committed", r.open.canUndo)
    }

    @Test
    fun `editing, undo, compose and export are rejected while recording`() = runTest {
        val r = rig()
        r.session.paint(fill(0..200))
        r.session.toggleRecord(overdub = true)
        // Straight after the call, before any collector has run: the guard must already hold.
        r.session.paint(fill(300..400))
        advance(200)
        val revision = r.open.revision
        r.session.paint(fill(300..400))
        r.session.undo()
        r.session.compose(ComposerParams(), null)
        val export = async { r.session.export("Nope") }
        advance(1_000)
        assertTrue(export.await().isFailure)
        assertTrue(r.exports.isEmpty())
        assertEquals(Phase.Recording, r.open.phase)
        assertTrue("only the take itself moved the revision", r.open.revision >= revision)
        assertEquals("no fill landed during the take", 0, r.open.track[350, rightZone])
        r.session.togglePlay()
        r.session.undo()
        assertEquals("undo removes the take, not the fill", GlyphZones.MAX_BRIGHTNESS, r.open.track[100, rightZone])
    }

    @Test
    fun `paint asks for a range and lights before applying`() = runTest {
        val r = rig()
        r.session.paint(PaintRequest(EditTool.FILL, null, setOf(rightZone), 1f, 2f))
        r.session.paint(PaintRequest(EditTool.FILL, 0..10, emptySet(), 1f, 2f))
        testScheduler.runCurrent()
        assertEquals(listOf("Drag across the timeline to pick a time range", "Tap the lights or pick a strip first"), r.messages)
        assertFalse(r.open.canUndo)
        r.session.paint(fill(0..10))
        testScheduler.runCurrent()
        assertEquals(GlyphZones.MAX_BRIGHTNESS, r.open.track[5, rightZone])
        assertTrue(r.open.canUndo)
    }

    @Test
    fun `compose regenerates in the background as one undo step`() = runTest {
        val r = rig()
        r.session.compose(ComposerParams(), 0..299)
        advance(10)
        val lit = (0..299).any { f -> GlyphZones.ALL.any { r.open.track[f, it] > 0 } }
        assertTrue(lit)
        assertTrue("outside the range stays dark", (300..599).none { f -> GlyphZones.ALL.any { r.open.track[f, it] > 0 } })
        r.session.undo()
        assertTrue((0..599).none { f -> GlyphZones.ALL.any { r.open.track[f, it] > 0 } })
    }

    @Test
    fun `edits are autosaved after a pause`() = runTest {
        val r = rig()
        r.session.paint(fill(0..10))
        advance(100)
        assertEquals(0, store.load(r.id).lights[5, rightZone])
        advance(600)
        assertEquals(GlyphZones.MAX_BRIGHTNESS, store.load(r.id).lights[5, rightZone])
    }

    @Test
    fun `close commits a running take and saves it`() = runTest {
        val r = rig()
        r.session.toggleRecord(overdub = true)
        r.session.pressPad(Pad.RIGHT, 1f)
        advance(500)
        r.session.close()
        assertEquals(GlyphZones.MAX_BRIGHTNESS, store.load(r.id).lights[10, rightZone])
    }

    @Test
    fun `export stops playback, reports progress, renames and returns to idle`() = runTest {
        val r = rig()
        r.session.paint(fill(0..10))
        r.session.togglePlay()
        advance(100)
        val export = async { r.session.export("Ring") }
        testScheduler.runCurrent()
        assertEquals(Phase.Exporting(0.5f), r.open.phase)
        advance(200)
        assertTrue(export.await().isSuccess)
        assertEquals(Phase.Idle, r.open.phase)
        assertEquals("Ring", r.open.name)
        assertEquals("Ring", store.load(r.id).meta.name)
        assertEquals(GlyphZones.MAX_BRIGHTNESS, r.exports.single().lights[5, rightZone])
        assertEquals("Saved to Ringtones/Glyph Studio", r.messages.last())
    }

    @Test
    fun `a failed export says why and returns to idle`() = runTest {
        val r = rig(exportFails = true)
        val export = async { r.session.export("Ring") }
        advance(200)
        assertTrue(export.await().isFailure)
        assertEquals(Phase.Idle, r.open.phase)
        assertEquals("Export failed: disk full", r.messages.last())
    }
}
