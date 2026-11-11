package com.mauray.glyp.ui.home

import com.mauray.glyp.model.ProjectMeta
import com.mauray.glyp.model.ProjectStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * The Compositions list and its undoable deletes: deleted projects are hidden at once and only
 * removed from disk by [commit] (or [commitPending] when the screen goes away for good).
 */
class ProjectList(private val store: ProjectStore, private val scope: CoroutineScope) {

    private val all = MutableStateFlow<List<ProjectMeta>>(emptyList())

    /** Projects deleted while their Undo is still on screen: hidden, but still on disk. */
    private val hidden = MutableStateFlow(emptySet<String>())

    val projects: StateFlow<List<ProjectMeta>> = combine(all, hidden) { list, gone -> list.filterNot { it.id in gone } }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun refresh() {
        scope.launch { all.value = store.list() }
    }

    /** Hides [ids] at once; [commit] deletes them, [undo] brings them back. */
    fun hide(ids: Set<String>) {
        hidden.update { it + ids }
    }

    fun undo(ids: Set<String>) {
        hidden.update { it - ids }
    }

    fun commit(ids: Set<String>) {
        scope.launch {
            ids.forEach { store.delete(it) }
            // Reload before un-hiding, so a deleted project never flashes back.
            all.value = store.list()
            hidden.update { it - ids }
        }
    }

    fun rename(id: String, name: String) {
        scope.launch {
            store.rename(id, name)
            refresh()
        }
    }

    /** Deletes whatever is still hidden; blocks, for `onCleared`. */
    fun commitPending() {
        // Leaving with an Undo still showing means the user didn't take it back.
        val ids = hidden.value
        if (ids.isNotEmpty()) runBlocking { ids.forEach { store.delete(it) } }
    }
}
