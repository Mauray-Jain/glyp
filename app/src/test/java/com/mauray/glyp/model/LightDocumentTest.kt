package com.mauray.glyp.model

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.concurrent.thread

class LightDocumentTest {

    private fun doc(frames: Int = 10, budget: Long = LightDocument.DEFAULT_UNDO_BUDGET) =
        LightDocument(LightTrack(frames), budget)

    @Test
    fun `edit is one undoable step`() {
        val d = doc()
        d.edit { it[0, 0] = 100; it[1, 0] = 200 }
        assertEquals(100, d[0, 0])
        assertTrue(d.canUndo)
        d.undo()
        assertEquals(0, d[0, 0])
        assertEquals(0, d[1, 0])
        d.redo()
        assertEquals(200, d[1, 0])
    }

    @Test
    fun `a new edit clears redo`() {
        val d = doc()
        d.edit { it[0, 0] = 1 }
        d.undo()
        assertTrue(d.canRedo)
        d.edit { it[0, 1] = 1 }
        assertFalse(d.canRedo)
    }

    @Test
    fun `revision and dirty follow changes and saves`() {
        val d = doc()
        assertFalse(d.dirty)
        d.edit { it[0, 0] = 5 }
        val rev = d.revision.value
        assertTrue(rev > 0)
        assertTrue(d.dirty)
        d.markSaved(rev)
        assertFalse(d.dirty)
        d.undo()
        assertTrue(d.revision.value > rev)
        assertTrue(d.dirty)
    }

    @Test
    fun `snapshot is independent of later edits`() {
        val d = doc()
        d.edit { it[3, 3] = 7 }
        val snap = d.snapshot()
        d.edit { it[3, 3] = 9 }
        assertEquals(7, snap[3, 3])
        snap[3, 3] = 1
        assertEquals(9, d[3, 3])
    }

    @Test
    fun `transaction publishes progress and commits as one step`() {
        val d = doc()
        val tx = d.begin()
        tx.update { it[0, 0] = 10 }
        val before = d.revision.value
        tx.publish()
        assertEquals(before + 1, d.revision.value)
        assertFalse("no undo while a take is open", d.canUndo)
        d.undo()
        assertEquals("undo is ignored while open", 10, d[0, 0])
        tx.update { it[1, 0] = 20 }
        tx.commit()
        d.undo()
        assertEquals(0, d[0, 0])
        assertEquals(0, d[1, 0])
    }

    @Test
    fun `untouched transaction leaves no undo step`() {
        val d = doc()
        d.begin().commit()
        assertFalse(d.canUndo)
    }

    @Test(expected = IllegalStateException::class)
    fun `only one transaction at a time`() {
        val d = doc()
        d.begin()
        d.begin()
    }

    @Test
    fun `undo history is bounded by the byte budget`() {
        val frames = 10
        val stepBytes = LightTrack(frames).sizeBytes
        val d = doc(frames, budget = stepBytes * 3)
        repeat(10) { i -> d.edit { it[0, 0] = i + 1 } }
        var undone = 0
        while (d.canUndo) { d.undo(); undone++ }
        // Three snapshots fit; the redo stack shares the budget, so undoing never grows it.
        assertEquals(3, undone)
        assertEquals(7, d[0, 0])
    }

    @Test
    fun `newest checkpoint survives even when one step exceeds the budget`() {
        val d = doc(budget = 1)
        d.edit { it[0, 0] = 1 }
        d.edit { it[0, 0] = 2 }
        d.undo()
        assertEquals(1, d[0, 0])
        assertFalse(d.canUndo)
    }

    @Test
    fun `use from another thread fails`() {
        val d = doc()
        var error: Throwable? = null
        thread { error = runCatching { d[0, 0] }.exceptionOrNull() }.join()
        assertTrue(error is IllegalStateException)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `background edit commits atomically as one step`() = runTest {
        val d = doc()
        d.editInBackground(StandardTestDispatcher(testScheduler)) { it[2, 2] = 42 }
        assertEquals(42, d[2, 2])
        d.undo()
        assertEquals(0, d[2, 2])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `background edit reruns when the document changed meanwhile`() = runTest {
        val d = doc()
        var runs = 0
        d.editInBackground(StandardTestDispatcher(testScheduler)) { track ->
            // The test dispatcher shares the test thread, so this stands in for a user edit
            // landing while the first computation is still running.
            if (++runs == 1) d.edit { it[0, 0] = 10 }
            // Doubles whatever is there, so a lost concurrent edit would show.
            track[0, 0] = track[0, 0] * 2 + 1
        }
        assertEquals(2, runs)
        assertEquals(21, d[0, 0])
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `background edit waits for an open take`() = runTest {
        val d = doc()
        val take = d.begin()
        take.update { it[0, 0] = 5 }
        val job = launch { d.editInBackground(StandardTestDispatcher(testScheduler)) { it[0, 0] = it[0, 0] + 1 } }
        testScheduler.advanceUntilIdle()
        assertFalse("still waiting", job.isCompleted)
        take.commit()
        job.join()
        assertEquals(6, d[0, 0])
        d.undo()
        assertEquals("compose undoes on its own", 5, d[0, 0])
    }
}
