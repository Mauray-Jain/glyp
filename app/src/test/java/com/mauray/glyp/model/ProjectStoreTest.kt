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
import java.io.IOException
import java.io.InputStream

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
    fun `rename keeps the lights and audio`() = runTest {
        val s = store()
        val p = s.create("a.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }
        s.save(p.meta, p.lights.copy().apply { this[5, 7] = 1234 })
        clock = 3_000
        val renamed = s.rename(p.meta.id, "Morning alarm")
        assertEquals("Morning alarm", renamed.name)
        assertEquals(3_000, renamed.updatedAt)
        val loaded = s.load(p.meta.id)
        assertEquals("Morning alarm", loaded.meta.name)
        assertEquals(1234, loaded.lights[5, 7])
        assertEquals("x", loaded.audioFile.readText())
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

    @Test
    fun `a failed write leaves no temp file and the old lights intact`() = runTest {
        val s = store()
        val p = s.create("a.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }
        s.save(p.meta, p.lights.copy().apply { this[0, 0] = 7 })
        File(tmp.root, "${p.meta.id}/lights.bin.tmp").mkdirs()
        runCatching { s.save(p.meta, p.lights.copy().apply { this[0, 0] = 99 }) }
        assertFalse(File(tmp.root, "${p.meta.id}/lights.bin.tmp").isFile)
        assertEquals(7, s.load(p.meta.id).lights[0, 0])
    }

    @Test
    fun `an import whose song can't be opened leaves no folder`() = runTest {
        val result = runCatching { store().create("a.ogg", 1_000, autoCompose = false) { throw IOException("gone") } }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(emptyList<String>(), tmp.root.list()!!.toList())
    }

    @Test
    fun `an import that fails mid-copy leaves no folder`() = runTest {
        val failing = object : InputStream() {
            private var reads = 0
            override fun read(): Int = throw IOException("unplugged")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (++reads > 1) throw IOException("unplugged")
                b.fill(1, off, off + len)
                return len
            }
        }
        val result = runCatching { store().create("a.ogg", 1_000, autoCompose = false) { failing } }
        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(emptyList<String>(), tmp.root.list()!!.toList())
    }

    @Test
    fun `list removes a folder abandoned for over an hour`() = runTest {
        clock = 10 * HOUR
        val abandoned = File(tmp.root, "dead").apply { mkdirs() }
        File(abandoned, "audio.mp3.tmp").writeText("half a song")
        abandoned.setLastModified(clock - 2 * HOUR)
        store().list()
        assertFalse(abandoned.exists())
    }

    @Test
    fun `list keeps a folder whose import may still be running`() = runTest {
        clock = 10 * HOUR
        val importing = File(tmp.root, "busy").apply { mkdirs() }
        importing.setLastModified(clock - 60_000)
        store().list()
        assertTrue(importing.exists())
    }

    @Test
    fun `the sweep never removes a project however old, only its stale temp files`() = runTest {
        val s = store()
        val p = s.create("a.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }
        val dir = File(tmp.root, p.meta.id)
        val staleTmp = File(dir, "lights.bin.tmp").apply { writeText("x") }
        clock = 100 * HOUR
        dir.listFiles()!!.forEach { it.setLastModified(1_000) }
        dir.setLastModified(1_000)
        assertEquals(listOf(p.meta.id), s.list().map { it.id })
        assertTrue(File(dir, "project.json").exists())
        assertFalse(staleTmp.exists())
    }

    private companion object {
        const val HOUR = 60 * 60_000L
    }
}
