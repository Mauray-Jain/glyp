package com.mauray.glyphstudio.ui.home

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mauray.glyphstudio.GlypApp
import com.mauray.glyphstudio.model.ProjectMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = (app as GlypApp).store

    private val _projects = MutableStateFlow<List<ProjectMeta>>(emptyList())
    val projects: StateFlow<List<ProjectMeta>> = _projects.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _projects.value = store.list() }
    }

    fun create(audio: Uri, autoCompose: Boolean, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val duration = withContext(Dispatchers.IO) { durationOf(audio) }
                require(duration > 0) { "Couldn't read the length of that file" }
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

    fun delete(id: String) {
        viewModelScope.launch {
            store.delete(id)
            refresh()
        }
    }

    fun clearError() {
        _error.value = null
    }

    private fun displayNameOf(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    private fun durationOf(uri: Uri): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(getApplication(), uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }
}
