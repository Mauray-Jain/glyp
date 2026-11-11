package com.mauray.glyp.ui.home

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mauray.glyp.GlypApp
import com.mauray.glyp.model.ProjectMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = (app as GlypApp).store

    private val all = MutableStateFlow<List<ProjectMeta>>(emptyList())

    /** Projects deleted while their Undo is still on screen: hidden, but still on disk. */
    private val hidden = MutableStateFlow(emptySet<String>())

    val projects: StateFlow<List<ProjectMeta>> = combine(all, hidden) { list, gone -> list.filterNot { it.id in gone } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        viewModelScope.launch { all.value = store.list() }
    }

    fun create(audio: Uri, autoCompose: Boolean, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val duration = withContext(Dispatchers.IO) { durationOf(audio) }
                require(duration > 0) { "That file isn't a song Glyp can read." }
                // The whole song is decoded into memory; longer ones don't fit on every phone.
                require(duration / 1000 <= MAX_SONG_MS / 1000) { "Pick a song up to ${MAX_SONG_MS / 60_000} minutes long." }
                val resolver = getApplication<Application>().contentResolver
                val fileName = withContext(Dispatchers.IO) { displayNameOf(audio) } ?: "Untitled"
                val project = store.create(fileName, duration, autoCompose) {
                    resolver.openInputStream(audio) ?: error("Couldn't open that file")
                }
                onCreated(project.meta.id)
            } catch (e: Exception) {
                _error.value = e.message ?: "Couldn't import that file"
            } finally {
                _busy.value = false
            }
        }
    }

    /** Hides [ids] at once; [commitDelete] deletes them, [undoDelete] brings them back. */
    fun delete(ids: Set<String>) {
        hidden.update { it + ids }
    }

    fun undoDelete(ids: Set<String>) {
        hidden.update { it - ids }
    }

    fun commitDelete(ids: Set<String>) {
        viewModelScope.launch {
            ids.forEach { store.delete(it) }
            all.value = store.list()
            hidden.update { it - ids }
        }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch {
            store.rename(id, name)
            refresh()
        }
    }

    // Leaving with an Undo still showing means the user didn't take it back.
    override fun onCleared() {
        val ids = hidden.value
        if (ids.isNotEmpty()) runBlocking { ids.forEach { store.delete(it) } }
    }

    fun clearError() {
        _error.value = null
    }

    private fun displayNameOf(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** The song's length, or 0 when the file can't be read as audio. */
    private fun durationOf(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(getApplication(), uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: RuntimeException) {
            0L
        } finally {
            retriever.release()
        }
    }

    private companion object {
        const val MAX_SONG_MS = 10 * 60_000L
    }
}
