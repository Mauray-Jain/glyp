package com.mauray.glyp.ui.studio

import com.mauray.glyp.audio.PcmAudio
import com.mauray.glyp.audio.Song
import com.mauray.glyp.composer.ComposerParams
import com.mauray.glyp.export.ExportJob
import com.mauray.glyp.export.Ringtone
import com.mauray.glyp.glyph.GlyphZones
import com.mauray.glyp.model.ProjectStore
import com.mauray.glyp.studio.EditTool
import com.mauray.glyp.studio.FakeClock
import com.mauray.glyp.studio.LightShapes
import com.mauray.glyp.studio.Pad
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
            loadSong = { _, frames, onProgress ->
                val rate = 8_000
                val click = ShortArray(rate * 10) { i -> if (i % (rate / 2) < 200) (sin(i * 0.3f) * 20_000).toInt().toShort() else 0 }
                Song.of(PcmAudio(click, rate, 1), frames, onProgress)
            },
            openClock = { FakeClock(backgroundScope, testScheduler, durationMs = 10_000) },
            exportRingtone = { job, onProgress ->
                exports += job
                // Like the real exporter, which reports progress from Dispatchers.IO.
                onAnotherThread { onProgress(0.5f) }
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

    /** Runs [block] on a fresh thread and waits for it, rethrowing what it threw. */
    private fun onAnotherThread(block: () -> Unit) {
        var thrown: Throwable? = null
        Thread { try { block() } catch (t: Throwable) { thrown = t } }.apply { start(); join() }
        thrown?.let { throw it }
    }

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
            loadSong = { _, _, _ -> error("unused") },
            openClock = { error("unused") },
            exportRingtone = { _, _ -> error("unused") },
            showLights = {},
            compute = StandardTestDispatcher(testScheduler),
        )
        testScheduler.runCurrent()
        assertTrue(session.state.value is StudioState.Failed)
    }

    @Test
    fun `a song too big for memory fails with a message instead of crashing`() = runTest {
        val id = kotlinx.coroutines.runBlocking {
            store.create("Long.ogg", 10_000, autoCompose = false) { "audio".byteInputStream() }.meta.id
        }
        val session = StudioSession(
            id, backgroundScope, store,
            loadSong = { _, _, _ -> throw OutOfMemoryError("52920016 byte allocation") },
            openClock = { error("unused") },
            exportRingtone = { _, _ -> error("unused") },
            showLights = {},
            compute = StandardTestDispatcher(testScheduler),
        )
        testScheduler.runCurrent()
        assertEquals("This song is too long for this phone's memory.", (session.state.value as StudioState.Failed).message)
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
    fun `a slider drag repaints the selection live as one undo step`() = runTest {
        val r = rig()
        r.session.paint(fill(0..10))
        testScheduler.runCurrent()
        for (level in listOf(0.2f, 0.6f, 0.4f)) {
            r.session.paintLive(fill(0..10).copy(brightness = level))
            testScheduler.runCurrent()
            assertEquals(LightShapes.stored(level), r.open.track[5, rightZone])
        }
        r.session.endLivePaint()
        testScheduler.runCurrent()
        r.session.undo()
        testScheduler.runCurrent()
        assertEquals("one undo goes back to before the drag", GlyphZones.MAX_BRIGHTNESS, r.open.track[5, rightZone])
    }

    @Test
    fun `a slider drag brings the playhead into the selection so the Glyphs show it`() = runTest {
        val r = rig()
        r.session.paintLive(PaintRequest(EditTool.FADE_IN, 100..200, setOf(rightZone), 0.5f, 2f))
        testScheduler.runCurrent()
        assertEquals("the brightest frame of a fade in is its end", 200, r.open.playhead)
        assertEquals(LightShapes.stored(0.5f), r.open.lights[rightZone])
        r.session.endLivePaint()
        r.session.seek(150)
        r.session.paintLive(PaintRequest(EditTool.FADE_IN, 100..200, setOf(rightZone), 0.8f, 2f))
        testScheduler.runCurrent()
        assertEquals("a playhead already inside stays put", 150, r.open.playhead)
    }

    @Test
    fun `a slider drag without a range or lights only sets the level`() = runTest {
        val r = rig()
        r.session.paintLive(PaintRequest(EditTool.FILL, null, setOf(rightZone), 0.5f, 2f))
        r.session.paintLive(PaintRequest(EditTool.FILL, 0..10, emptySet(), 0.5f, 2f))
        r.session.endLivePaint()
        testScheduler.runCurrent()
        assertTrue("no nagging while dragging", r.messages.isEmpty())
        assertFalse(r.open.canUndo)
    }

    @Test
    fun `recording during a slider drag commits the live paint first`() = runTest {
        val r = rig()
        r.session.paintLive(fill(0..10).copy(brightness = 0.5f))
        r.session.toggleRecord(overdub = true)
        advance(100)
        assertEquals(Phase.Recording, r.open.phase)
        r.session.paintLive(fill(0..10))
        r.session.endLivePaint()
        r.session.togglePlay()
        testScheduler.runCurrent()
        assertEquals("ignored while recording", LightShapes.stored(0.5f), r.open.track[5, rightZone])
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
        assertEquals("Saved to Ringtones/Glyp", r.messages.last())
    }

    @Test
    fun `a cancelled export saves nothing and returns to idle`() = runTest {
        val r = rig()
        val export = launch { r.session.export("Ring") }
        testScheduler.runCurrent()
        assertTrue(r.open.phase is Phase.Exporting)
        export.cancel()
        testScheduler.runCurrent()
        assertEquals(Phase.Idle, r.open.phase)
        assertFalse("no Saved notice", r.messages.any { "Saved" in it })
        r.session.paint(fill(0..10))
        testScheduler.runCurrent()
        assertTrue("editing works again", r.open.canUndo)
    }

    @Test
    fun `a failed export says why and returns to idle`() = runTest {
        val r = rig(exportFails = true)
        val export = async { r.session.export("Ring") }
        advance(200)
        assertEquals("disk full", export.await().exceptionOrNull()?.message)
        assertEquals(Phase.Idle, r.open.phase)
        assertTrue("the dialog shows the reason, not a snackbar", r.messages.none { "disk full" in it })
    }
}
