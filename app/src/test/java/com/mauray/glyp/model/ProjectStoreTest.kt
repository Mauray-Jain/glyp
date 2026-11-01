package com.mauray.glyp.model

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ProjectStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L
    private fun store() = ProjectStore(tmp.root, Dispatchers.Unconfined, now = { clock })

    @Test
    fun `create copies the audio and sizes the track to the song`() = runTest {
        val p = store().create("Song.mp3", durationMs = 2_000, autoCompose = true) { "audio".byteInputStream() }
        assertEquals("Song", p.meta.name)
        assertEquals("audio", p.audioFile.readText())
        assertEquals("mp3", p.audioFile.extension)
        assertEquals(120, p.lights.frameCount)
        assertTrue(p.meta.pendingAutoCompose)
    }

    @Test
    fun `save and load round trip`() = runTest {
        val s = store()
        val p = s.create("a.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }
        val lights = p.lights.copy().apply { this[5, 7] = 1234; this[59, 35] = 4095 }
        clock = 2_000
        val saved = s.save(p.meta.copy(name = "Renamed"), lights)
        assertEquals(2_000, saved.updatedAt)
        val loaded = s.load(p.meta.id)
        assertEquals("Renamed", loaded.meta.name)
        assertEquals(1234, loaded.lights[5, 7])
        assertEquals(4095, loaded.lights[59, 35])
        assertEquals(p.audioFile, loaded.audioFile)
    }

    @Test
    fun `saves leave no temp files behind`() = runTest {
        val s = store()
        val p = s.create("a.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }
        s.save(p.meta, p.lights)
        val dir = File(tmp.root, p.meta.id)
        assertFalse(dir.list()!!.any { it.endsWith(".tmp") })
    }

    @Test
    fun `a failed write keeps the previous version`() = runTest {
        val s = store()
        val p = s.create("a.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }
        // A directory where the temp file should go makes the next write fail before the rename.
        File(tmp.root, "${p.meta.id}/lights.bin.tmp").mkdirs()
        val changed = p.lights.copy().apply { this[0, 0] = 99 }
        runCatching { s.save(p.meta, changed) }
        assertEquals(0, s.load(p.meta.id).lights[0, 0])
    }

    @Test
    fun `list is newest first and skips broken folders`() = runTest {
        val s = store()
        val old = s.create("old.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }
        clock = 5_000
        val new = s.create("new.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }
        File(tmp.root, "junk").mkdirs()
        assertEquals(listOf(new.meta.id, old.meta.id), s.list().map { it.id })
        s.delete(old.meta.id)
        assertEquals(listOf(new.meta.id), s.list().map { it.id })
    }
}
