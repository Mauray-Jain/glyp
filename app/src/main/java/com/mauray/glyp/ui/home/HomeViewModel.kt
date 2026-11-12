package com.mauray.glyp.ui.home

import android.app.Application
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mauray.glyp.GlypApp
import com.mauray.glyp.export.GlyphRingtone
import com.mauray.glyp.export.OggOpus
import com.mauray.glyp.model.ProjectMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val store = (app as GlypApp).store

    private val list = ProjectList(store, viewModelScope)

    val projects: StateFlow<List<ProjectMeta>> = list.projects

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun refresh() = list.refresh()

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
                // A Glyph ringtone (Glyp's own, or one from Nothing's Glyph Composer) brings
                // its lights along, so it can be edited instead of composed from scratch.
                val opus = withContext(Dispatchers.IO) { readIfOggOpus(audio) }
                val lights = opus?.let { GlyphRingtone.decodeLights(OggOpus.readTags(it)) }
                val project = store.create(fileName, duration, autoCompose, lights) {
                    opus?.inputStream() ?: resolver.openInputStream(audio) ?: error("Couldn't open that file")
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
    fun delete(ids: Set<String>) = list.hide(ids)

    fun undoDelete(ids: Set<String>) = list.undo(ids)

    fun commitDelete(ids: Set<String>) = list.commit(ids)

    fun rename(id: String, name: String) = list.rename(id, name)

    override fun onCleared() = list.commitPending()

    fun clearError() {
        _error.value = null
    }

    private fun displayNameOf(uri: Uri): String? =
        getApplication<Application>().contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }

    /** The whole file when it is Ogg Opus (a few MB at most: songs are up to 10 minutes), else null. */
    private fun readIfOggOpus(uri: Uri): ByteArray? =
        getApplication<Application>().contentResolver.openInputStream(uri)?.use { input ->
            val head = input.readNBytes(64)
            if (OggOpus.isOggOpus(head)) head + input.readBytes() else null
        }

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
