package com.mauray.glyp.ui.studio

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mauray.glyp.GlypApp
import com.mauray.glyp.audio.MediaPlayerClock
import com.mauray.glyp.audio.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Builds the [StudioSession] for one project from the app's Android services, and closes it
 * (take committed, last edit saved, lights off) when the user leaves the Studio.
 */
class StudioViewModel(app: Application, projectId: String) : ViewModel() {

    private val glyphApp = app as GlypApp
    val glyphState = glyphApp.glyph.state
    val controls = StudioControls()

    val session = StudioSession(
        projectId = projectId,
        scope = viewModelScope,
        store = glyphApp.store,
        loadSong = { file, frames, onProgress -> withContext(Dispatchers.Default) { Song.decode(file, frames, onProgress) } },
        openClock = { file -> withContext(Dispatchers.IO) { MediaPlayerClock(file) } },
        exportRingtone = glyphApp.exporter::export,
        showLights = glyphApp.glyph::show,
        compute = Dispatchers.Default,
    )

    override fun onCleared() {
        session.close()
        glyphApp.glyph.off()
    }
}
