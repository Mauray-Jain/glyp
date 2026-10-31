package com.mauray.glyphstudio.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

/**
 * The one owner of a project's light track while it is open.
 *
 * Every change is a transaction (checkpoint, mutate, commit) and is undoable as one step.
 * Readers get this document as [LightFrames] or take a [snapshot]; nobody else holds the
 * mutable track. All calls must come from the thread that created the document (main in the
 * app); work on other threads runs on a snapshot and is committed back with [editInBackground].
 *
 * Undo and redo keep whole snapshots, oldest dropped first once they use more than
 * [undoBudgetBytes] (the newest checkpoint is always kept).
 */
class LightDocument(initial: LightTrack, private val undoBudgetBytes: Long = DEFAULT_UNDO_BUDGET) : LightFrames {

    private val owner = Thread.currentThread()
    private var track = initial
    private val undoStack = ArrayDeque<LightTrack>()
    private val redoStack = ArrayDeque<LightTrack>()
    private var open: Transaction? = null

    private val _revision = MutableStateFlow(0)

    /** Bumped on every published change, undo and redo; views redraw and autosave on it. */
    val revision: StateFlow<Int> = _revision.asStateFlow()
    private var savedRevision = 0

    /** True when there are changes since the last [markSaved]. */
    val dirty: Boolean get() = confined { _revision.value != savedRevision }

    val canUndo: Boolean get() = confined { open == null && undoStack.isNotEmpty() }
    val canRedo: Boolean get() = confined { open == null && redoStack.isNotEmpty() }

    override val frameCount: Int get() = track.frameCount

    override fun get(frame: Int, zone: Int): Int = confined { track[frame, zone] }

    override fun copyFrame(frame: Int, out: ShortArray) = confined { track.copyFrame(frame, out) }

    /** An independent copy, safe to hand to another thread. */
    fun snapshot(): LightTrack = confined { track.copy() }

    /** Records that the content as of [revision] has been written to disk. */
    fun markSaved(revision: Int) = confined { savedRevision = revision }

    /** One-shot change as a single undo step. */
    fun edit(change: (LightTrack) -> Unit) {
        val tx = begin()
        tx.update(change)
        tx.commit()
    }

    /**
     * Opens a transaction that can be updated many times and is undone as one step, e.g. a
     * recording take. Only one transaction can be open; undo and redo wait for it to commit.
     */
    fun begin(): Transaction = confined {
        check(open == null) { "A transaction is already open" }
        undoStack.addLast(track.copy())
        Transaction().also { open = it }
    }

    /**
     * Runs [change] on a snapshot in [context] and commits the result as one undo step. If the
     * document changed while it ran, it starts over on the new content, so no edit is lost.
     */
    suspend fun editInBackground(context: CoroutineContext, change: (LightTrack) -> Unit) {
        while (true) {
            val base = revision.value
            val work = snapshot()
            withContext(context) { change(work) }
            if (confined { base == _revision.value && open == null }) {
                undoStack.addLast(track)
                track = work
                redoStack.clear()
                changed()
                return
            }
        }
    }

    fun undo() = swap(undoStack, redoStack)

    fun redo() = swap(redoStack, undoStack)

    private fun swap(from: ArrayDeque<LightTrack>, to: ArrayDeque<LightTrack>) = confined {
        if (open != null) return@confined
        val previous = from.removeLastOrNull() ?: return@confined
        to.addLast(track)
        track = previous
        changed()
    }

    private fun changed() {
        trimHistory()
        _revision.value++
    }

    private fun trimHistory() {
        val stepBytes = track.sizeBytes
        while (undoStack.size > 1 && (undoStack.size + redoStack.size) * stepBytes > undoBudgetBytes) {
            undoStack.removeFirst()
        }
    }

    private inline fun <T> confined(block: () -> T): T {
        check(Thread.currentThread() === owner) { "LightDocument used off its owner thread" }
        return block()
    }

    /** A change in progress. [update] mutates; [publish] and [commit] tell readers about it. */
    inner class Transaction internal constructor() {
        private var touched = false

        fun update(change: (LightTrack) -> Unit) = confined {
            check(open === this) { "Transaction already committed" }
            change(track)
            touched = true
        }

        /** Bumps the revision so views redraw, without ending the transaction. */
        fun publish() = confined {
            check(open === this) { "Transaction already committed" }
            _revision.value++
        }

        /** Ends the transaction; one that never changed anything leaves no undo step. */
        fun commit() = confined {
            check(open === this) { "Transaction already committed" }
            open = null
            if (touched) {
                redoStack.clear()
                changed()
            } else {
                undoStack.removeLast()
            }
        }
    }

    companion object {
        const val DEFAULT_UNDO_BUDGET = 32L * 1024 * 1024
    }
}
