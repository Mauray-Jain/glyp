package com.mauray.glyphstudio.ui.studio

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mauray.glyphstudio.GlyphStudioApp
import com.mauray.glyphstudio.audio.AudioDecoder
import com.mauray.glyphstudio.audio.MediaPlayerClock
import com.mauray.glyphstudio.composer.Analysis
import com.mauray.glyphstudio.composer.AudioAnalyzer
import com.mauray.glyphstudio.composer.AutoComposer
import com.mauray.glyphstudio.composer.ComposerParams
import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.model.LightDocument
import com.mauray.glyphstudio.model.ProjectMeta
import com.mauray.glyphstudio.studio.EditOps
import com.mauray.glyphstudio.studio.EditTool
import com.mauray.glyphstudio.studio.Pad
import com.mauray.glyphstudio.studio.TakeRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.abs
import java.io.File
import kotlin.math.max

data class StudioUiState(
    val loading: String? = "Loading…",
    val progress: Float = 0f,
    val name: String = "",
    val frameCount: Int = 0,
    val playhead: Int = 0,
    val playing: Boolean = false,
    val selection: IntRange? = null,
    val selectedZones: Set<Int> = emptySet(),
    val composer: ComposerParams = ComposerParams(),
    val tool: EditTool = EditTool.FILL,
    val brightness: Float = 1f,
    val pulseRate: Float = 2f,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Bumped whenever the light track changes so the timeline redraws. */
    val revision: Int = 0,
    /** Record mode: song is playing and pad hits are being written into the track. */
    val recording: Boolean = false,
    /** Layer pad hits over existing lights (true) or replace them as the song plays (false). */
    val overdub: Boolean = true,
    val padBrightness: Float = 1f,
    val heldPads: Set<Pad> = emptySet(),
    /** Stored values the phone shows right now: the track at the playhead with pads on top. */
    val lights: IntArray = IntArray(GlyphZones.COUNT),
    val exportProgress: Float? = null,
    val exportedUri: Uri? = null,
    val message: String? = null,
)

class StudioViewModel(app: Application) : AndroidViewModel(app), StudioActions {

    private val glyphApp = app as GlyphStudioApp
    private val store = glyphApp.store
    private val exporter = glyphApp.exporter
    val glyphState = glyphApp.glyph.state

    private val _state = MutableStateFlow(StudioUiState())
    val state: StateFlow<StudioUiState> = _state.asStateFlow()

    private var meta: ProjectMeta? = null
    private var audioFile: File? = null

    /** The open project's lights; null until loaded. */
    var document: LightDocument? = null
        private set
    var analysis: Analysis? = null
        private set
    /** Peak amplitude per light frame, 0..1, for the timeline waveform. */
    var waveform: FloatArray = FloatArray(0)
        private set

    /** Playback, pads and takes; null until loaded. */
    private var recorder: TakeRecorder? = null

    fun load(id: String) {
        if (document != null) return
        viewModelScope.launch {
            try {
                val p = store.load(id)
                meta = p.meta
                audioFile = p.audioFile
                _state.update { it.copy(name = p.meta.name, frameCount = p.lights.frameCount, loading = "Listening to the track…") }
                val (a, wave) = withContext(Dispatchers.Default) {
                    val pcm = AudioDecoder.decode(p.audioFile) { pr -> _state.update { it.copy(progress = pr * 0.4f) } }
                    val mono = pcm.toMono()
                    val a = AudioAnalyzer.analyze(mono, pcm.sampleRate, p.lights.frameCount) { pr ->
                        _state.update { it.copy(progress = 0.4f + pr * 0.6f) }
                    }
                    a to peaks(mono, pcm.sampleRate, p.lights.frameCount)
                }
                analysis = a
                waveform = wave
                // A new project (unless started blank) gets its automatic composition once.
                if (p.meta.pendingAutoCompose) {
                    withContext(Dispatchers.Default) { AutoComposer.compose(a, _state.value.composer, p.lights) }
                    meta = store.save(p.meta.copy(pendingAutoCompose = false), p.lights)
                }
                val doc = LightDocument(p.lights)
                document = doc
                // Debounced autosave: half a second after the last change, from a snapshot.
                viewModelScope.launch { doc.revision.drop(1).collectLatest { delay(500); save() } }
                val clock = withContext(Dispatchers.IO) { MediaPlayerClock(p.audioFile) }
                val rec = TakeRecorder(doc, clock, viewModelScope, glyphApp.glyph::show)
                recorder = rec
                viewModelScope.launch {
                    rec.live.collect { live ->
                        _state.update {
                            it.copy(
                                playhead = live.playhead,
                                playing = live.playing,
                                recording = live.recording,
                                heldPads = live.heldPads,
                                lights = live.lights,
                            )
                        }
                    }
                }
                // Every change to the lights redraws the timeline and the Glyphs.
                viewModelScope.launch {
                    doc.revision.collect { rev ->
                        _state.update { it.copy(revision = rev, canUndo = doc.canUndo, canRedo = doc.canRedo) }
                    }
                }
                _state.update { it.copy(loading = null) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = null, message = "Couldn't open project: ${e.message}") }
            }
        }
    }

    // region playback

    override fun togglePlay() = when {
        _state.value.recording -> toggleRecord()
        _state.value.playing -> pause()
        else -> recorder?.play() ?: Unit
    }

    /** Pauses playback, keeping any take. */
    fun pause() {
        recorder?.pause()
    }

    override fun seek(frame: Int) {
        recorder?.seek(frame)
    }

    // endregion

    // region selection

    override fun setSelection(range: IntRange?) {
        _state.update { it.copy(selection = range?.takeIf { r -> r.last > r.first }) }
    }

    override fun toggleZone(zone: Int) = _state.update {
        it.copy(selectedZones = if (zone in it.selectedZones) it.selectedZones - zone else it.selectedZones + zone)
    }

    /** Toggles a whole strip (or, for null, every zone) in or out of the selection. */
    override fun selectStrip(strip: GlyphZones.Strip?) = _state.update {
        val zones = (strip?.zones ?: GlyphZones.ALL).toSet()
        it.copy(selectedZones = if (it.selectedZones.containsAll(zones)) it.selectedZones - zones else it.selectedZones + zones)
    }

    override fun clearZones() = _state.update { it.copy(selectedZones = emptySet()) }

    // endregion

    // region editing

    override fun setComposer(params: ComposerParams) = _state.update { it.copy(composer = params) }
    override fun setTool(tool: EditTool) = _state.update { it.copy(tool = tool) }
    override fun setBrightness(v: Float) = _state.update { it.copy(brightness = v) }
    override fun setPulseRate(v: Float) = _state.update { it.copy(pulseRate = v) }

    /** Regenerates the selection (or the whole song) with the current composer settings. */
    override fun autoCompose(selectionOnly: Boolean) {
        val doc = document ?: return
        val a = analysis ?: return
        val s = _state.value
        val range = if (selectionOnly) s.selection ?: return else 0 until doc.frameCount
        viewModelScope.launch {
            doc.editInBackground(Dispatchers.Default) { AutoComposer.compose(a, s.composer, it, range) }
        }
    }

    override fun applyTool() = apply(_state.value.tool)

    /** Turns the selected lights off over the selected time range. */
    override fun clearLights() = apply(EditTool.CLEAR)

    private fun apply(tool: EditTool) {
        val doc = document ?: return
        val s = _state.value
        val range = s.selection
        if (range == null) {
            _state.update { it.copy(message = "Drag across the timeline to pick a time range") }
            return
        }
        if (s.selectedZones.isEmpty()) {
            _state.update { it.copy(message = "Tap the lights or pick a strip first") }
            return
        }
        // Sweeps travel in strip order C → A → B, each strip in its own zone order.
        val zones = GlyphZones.stripOrder.flatMap { strip -> strip.zones.filter { it in s.selectedZones } }
        doc.edit { EditOps.apply(it, tool, range, zones, s.brightness, s.pulseRate) }
    }

    override fun undo() { document?.undo() }
    override fun redo() { document?.redo() }

    /** Writes a snapshot if anything changed since the last save. */
    private suspend fun save() {
        val doc = document ?: return
        val m = meta ?: return
        if (!doc.dirty && m.name == _state.value.name) return
        val revision = doc.revision.value
        meta = store.save(m.copy(name = _state.value.name), doc.snapshot())
        doc.markSaved(revision)
    }

    fun rename(name: String) {
        _state.update { it.copy(name = name) }
        viewModelScope.launch { save() }
    }

    // endregion

    // region record

    override fun setOverdub(overdub: Boolean) = _state.update { it.copy(overdub = overdub) }
    override fun setPadBrightness(v: Float) = _state.update { it.copy(padBrightness = v) }

    override fun pressPad(pad: Pad) {
        recorder?.pressPad(pad, _state.value.padBrightness)
    }

    override fun releasePad(pad: Pad) {
        recorder?.releasePad(pad)
    }

    /** Starts recording from the playhead (song playing), or stops and keeps the take. */
    override fun toggleRecord() {
        val rec = recorder ?: return
        if (_state.value.recording) {
            rec.stop()
        } else {
            _state.update { it.copy(selection = null) }
            rec.record(_state.value.overdub)
        }
    }

    // endregion

    // region export

    fun export(title: String) {
        val doc = document ?: return
        val audio = audioFile ?: return
        val m = meta ?: return
        pause()
        viewModelScope.launch {
            _state.update { it.copy(exportProgress = 0f, exportedUri = null) }
            try {
                rename(title)
                val uri = exporter.export(audio, doc.snapshot(), m.durationMs, title) { pr ->
                    _state.update { it.copy(exportProgress = pr) }
                }
                _state.update { it.copy(exportProgress = null, exportedUri = uri, message = "Saved to Ringtones/Glyph Studio") }
            } catch (e: Exception) {
                _state.update { it.copy(exportProgress = null, message = "Export failed: ${e.message}") }
            }
        }
    }

    fun canSetDefaultRingtone() = exporter.canSetDefault()
    fun writeSettingsIntent() = exporter.writeSettingsIntent()

    fun setAsRingtone() {
        val uri = _state.value.exportedUri ?: return
        val ok = exporter.setAsDefaultRingtone(uri)
        _state.update { it.copy(message = if (ok) "Set as your ringtone" else "Allow \"Modify system settings\" first") }
    }

    fun dismissExport() = _state.update { it.copy(exportedUri = null) }

    // endregion

    fun consumeMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        // Leaving mid-take keeps what was recorded.
        recorder?.close()
        glyphApp.glyph.off()
        // viewModelScope is gone; a short blocking save keeps the last edit.
        runBlocking { save() }
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
