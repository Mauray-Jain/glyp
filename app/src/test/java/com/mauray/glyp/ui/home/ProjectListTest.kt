package com.mauray.glyp.ui.home

import com.mauray.glyp.model.ProjectMeta
import com.mauray.glyp.model.ProjectStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectListTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var clock = 1_000L
    private val store by lazy { ProjectStore(tmp.root, Dispatchers.Unconfined, now = { clock++ }) }

    private suspend fun project(name: String): String =
        store.create("$name.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }.meta.id

    /** A list with [names] created on disk and loaded. */
    private suspend fun TestScope.listOf(vararg names: String): Pair<ProjectList, List<String>> {
        val ids = names.map { project(it) }
        val list = ProjectList(store, backgroundScope)
        list.refresh()
        testScheduler.runCurrent()
        return list to ids
    }

    private fun ProjectList.ids() = projects.value.map(ProjectMeta::id).toSet()

    private fun onDisk(id: String) = File(tmp.root, id).exists()

    @Test
    fun `hide removes a project from the list but keeps it on disk`() = runTest {
        val (list, ids) = listOf("a", "b")
        list.hide(setOf(ids[0]))
        testScheduler.runCurrent()
        assertEquals(setOf(ids[1]), list.ids())
        assertTrue(onDisk(ids[0]))
    }

    @Test
    fun `undo brings a hidden project back untouched`() = runTest {
        val (list, ids) = listOf("a")
        val before = File(tmp.root, ids[0]).list()!!.toSet()
        list.hide(setOf(ids[0]))
        testScheduler.runCurrent()
        list.undo(setOf(ids[0]))
        testScheduler.runCurrent()
        assertEquals(setOf(ids[0]), list.ids())
        assertEquals(before, File(tmp.root, ids[0]).list()!!.toSet())
    }

    @Test
    fun `commit deletes the folder and the project stays out of the list`() = runTest {
        val (list, ids) = listOf("a", "b")
        list.hide(setOf(ids[0]))
        list.commit(setOf(ids[0]))
        testScheduler.runCurrent()
        assertFalse(onDisk(ids[0]))
        assertEquals(setOf(ids[1]), list.ids())
    }

    @Test
    fun `committing one of two hidden projects leaves the other hidden and on disk`() = runTest {
        val (list, ids) = listOf("a", "b", "c")
        list.hide(setOf(ids[0], ids[1]))
        list.commit(setOf(ids[0]))
        testScheduler.runCurrent()
        assertFalse(onDisk(ids[0]))
        assertTrue(onDisk(ids[1]))
        assertEquals(setOf(ids[2]), list.ids())
    }

    @Test
    fun `refresh keeps hidden projects hidden`() = runTest {
        val (list, ids) = listOf("a", "b")
        list.hide(setOf(ids[0]))
        list.refresh()
        testScheduler.runCurrent()
        assertEquals(setOf(ids[1]), list.ids())
    }

    @Test
    fun `commitPending deletes every project still hidden`() = runTest {
        val (list, ids) = listOf("a", "b", "c")
        list.hide(setOf(ids[0], ids[1]))
        testScheduler.runCurrent()
        list.commitPending()
        assertFalse(onDisk(ids[0]))
        assertFalse(onDisk(ids[1]))
        assertTrue(onDisk(ids[2]))
    }

    @Test
    fun `rename updates the list and the project on disk`() = runTest {
        val (list, ids) = listOf("a")
        list.rename(ids[0], "Chorus")
        testScheduler.runCurrent()
        assertEquals("Chorus", list.projects.value.single().name)
        assertEquals("Chorus", store.load(ids[0]).meta.name)
    }

    @Test
    fun `a committed project never reappears while the commit runs`() = runTest {
        val (list, ids) = listOf("a", "b")
        val seen = mutableListOf<Set<String>>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            list.projects.collect { seen += it.map(ProjectMeta::id).toSet() }
        }
        list.hide(setOf(ids[0]))
        testScheduler.runCurrent()
        val afterHide = seen.size
        list.commit(setOf(ids[0]))
        testScheduler.runCurrent()
        assertTrue("emissions after hide: $seen", seen.drop(afterHide - 1).none { ids[0] in it })
        assertEquals(setOf(ids[1]), seen.last())
    }
}
