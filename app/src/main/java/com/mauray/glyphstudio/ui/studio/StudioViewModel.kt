package com.mauray.glyphstudio.ui.studio

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.mauray.glyphstudio.GlyphStudioApp
import com.mauray.glyphstudio.audio.AudioDecoder
import com.mauray.glyphstudio.audio.MediaPlayerClock
import com.mauray.glyphstudio.composer.AudioAnalyzer
import com.mauray.glyphstudio.glyph.GlyphZones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max

/**
 * Builds the [StudioSession] for one project from the app's Android services, and closes it
 * (take committed, last edit saved, lights off) when the user leaves the Studio.
 */
class StudioViewModel(app: Application, projectId: String) : ViewModel() {

    private val glyphApp = app as GlyphStudioApp
    val glyphState = glyphApp.glyph.state
    val controls = StudioControls()

    val session = StudioSession(
        projectId = projectId,
        scope = viewModelScope,
        store = glyphApp.store,
        analyze = { file, frames, onProgress ->
            withContext(Dispatchers.Default) {
                val pcm = AudioDecoder.decode(file) { onProgress(it * 0.4f) }
                val mono = pcm.toMono()
                val analysis = AudioAnalyzer.analyze(mono, pcm.sampleRate, frames) { onProgress(0.4f + it * 0.6f) }
                analysis to peaks(mono, pcm.sampleRate, frames)
            }
        },
        openClock = { file -> withContext(Dispatchers.IO) { MediaPlayerClock(file) } },
        exportRingtone = glyphApp.exporter::export,
        showLights = glyphApp.glyph::show,
        compute = Dispatchers.Default,
    )

    override fun onCleared() {
        session.close()
        glyphApp.glyph.off()
    }

    private fun peaks(mono: FloatArray, sampleRate: Int, frames: Int): FloatArray {
        val out = FloatArray(frames)
        val perFrame = sampleRate.toDouble() / GlyphZones.FPS
        for (t in 0 until frames) {
            val from = (t * perFrame).toInt()
            val to = minOf(mono.size, ((t + 1) * perFrame).toInt())
            var peak = 0f
            for (i in from until to) peak = max(peak, abs(mono[i]))
            out[t] = peak
        }
        val ref = out.maxOrNull()?.takeIf { it > 0f } ?: 1f
        for (i in out.indices) out[i] /= ref
        return out
    }
}
