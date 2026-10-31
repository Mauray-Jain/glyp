package com.mauray.glyphstudio.ui.studio

import android.app.Application
import android.media.MediaPlayer
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mauray.glyphstudio.GlyphStudioApp
import com.mauray.glyphstudio.audio.AudioDecoder
import com.mauray.glyphstudio.composer.Analysis
import com.mauray.glyphstudio.composer.AudioAnalyzer
import com.mauray.glyphstudio.composer.AutoComposer
import com.mauray.glyphstudio.composer.ComposerParams
import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.model.Project
import com.mauray.glyphstudio.model.frameToMs
import com.mauray.glyphstudio.model.msToFrame
import com.mauray.glyphstudio.studio.EditOps
import com.mauray.glyphstudio.studio.EditTool
import com.mauray.glyphstudio.studio.Pad
import com.mauray.glyphstudio.studio.PadPerformance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.abs
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
    /** Live pad levels shown on the preview while jamming (not recording); null when idle. */
    val liveFrame: IntArray? = null,
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

    var project: Project? = null
        private set
    var analysis: Analysis? = null
        private set
    /** Peak amplitude per light frame, 0..1, for the timeline waveform. */
    var waveform: FloatArray = FloatArray(0)
        private set

    private var player: MediaPlayer? = null
    private var playbackJob: Job? = null
    private val undo = ArrayDeque<ShortArray>()
    private val redo = ArrayDeque<ShortArray>()

    private val performance = PadPerformance()
    private val padLevels = FloatArray(GlyphZones.COUNT)
    private val live = ShortArray(GlyphZones.COUNT)
    private var jamJob: Job? = null
    private var jamStartNanos = 0L
    /** Last frame written by the current take. */
    private var lastRecorded = -1

    fun load(id: String) {
        if (project != null) return
        viewModelScope.launch {
            try {
                val p = store.load(id)
                project = p
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
                    project = store.save(p, pendingAutoCompose = false)
                }
                player = MediaPlayer().apply {
                    setDataSource(p.audioFile.absolutePath)
                    prepare()
                    // Reaching the end of the song also ends a take, keeping what was recorded.
                    setOnCompletionListener { if (_state.value.recording) finishRecording() else pause() }
                }
                _state.update { it.copy(loading = null, revision = it.revision + 1) }
                showFrame(0)
            } catch (e: Exception) {
                _state.update { it.copy(loading = null, message = "Couldn't open project: ${e.message}") }
            }
        }
    }

    // region playback

    override fun togglePlay() = when {
        _state.value.recording -> toggleRecord()
        _state.value.playing -> pause()
        else -> play()
    }

    fun play() {
        val mp = player ?: return
        val s = _state.value
        val start = if (s.playhead >= s.frameCount - 1) 0 else s.playhead
        mp.seekTo(frameToMs(start), MediaPlayer.SEEK_CLOSEST)
        mp.start()
        // Pad hits are timed on the song clock from here on; drop any wall-clock jam.
        stopJam()
        _state.update { it.copy(playing = true) }
        playbackJob?.cancel()
        playbackJob = viewModelScope.launch {
            var ticks = 0
            while (isActive && mp.isPlaying) {
                val frame = msToFrame(mp.currentPosition.toLong()).coerceIn(0, max(0, s.frameCount - 1))
                if (frame != _state.value.playhead) {
                    _state.update { it.copy(playhead = frame) }
                    when {
                        _state.value.recording -> {
                            recordUpTo(frame)
                            // Let the timeline catch up ~10x a second while recording.
                            if (++ticks % 6 == 0) _state.update { it.copy(revision = it.revision + 1) }
                            showFrame(frame)
                        }
                        !performance.isIdle(frame) -> showLive(frame, frame)
                        else -> {
                            if (_state.value.liveFrame != null) _state.update { it.copy(liveFrame = null) }
                            showFrame(frame)
                        }
                    }
                }
                delay(8)
            }
            if (_state.value.recording) finishRecording() else _state.update { it.copy(playing = false) }
        }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        playbackJob?.cancel()
        performance.clear()
        _state.update { it.copy(playing = false, heldPads = emptySet(), liveFrame = null) }
    }

    override fun seek(frame: Int) {
        if (_state.value.recording) return
        val f = frame.coerceIn(0, max(0, _state.value.frameCount - 1))
        _state.update { it.copy(playhead = f) }
        player?.let { if (it.isPlaying) it.seekTo(frameToMs(f), MediaPlayer.SEEK_CLOSEST) }
        showFrame(f)
    }

    /** Pushes the given frame to the real Glyph lights. */
    fun showFrame(frame: Int) {
        val track = project?.lights ?: return
        if (frame in 0 until track.frameCount) glyphApp.glyph.show(track.data, track.offsetOf(frame))
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
        val p = project ?: return
        val a = analysis ?: return
        val s = _state.value
        val range = if (selectionOnly) s.selection ?: return else 0 until p.lights.frameCount
        viewModelScope.launch {
            checkpoint()
            withContext(Dispatchers.Default) { AutoComposer.compose(a, s.composer, p.lights, range) }
            changed()
        }
    }

    override fun applyTool() = apply(_state.value.tool)

    /** Turns the selected lights off over the selected time range. */
    override fun clearLights() = apply(EditTool.CLEAR)

    private fun apply(tool: EditTool) {
        val p = project ?: return
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
        checkpoint()
        // Sweeps travel in strip order C → A → B, each strip in its own zone order.
        val zones = GlyphZones.stripOrder.flatMap { strip -> strip.zones.filter { it in s.selectedZones } }
        EditOps.apply(p.lights, tool, range, zones, s.brightness, s.pulseRate)
        changed()
    }

    override fun undo() = swapHistory(undo, redo)
    override fun redo() = swapHistory(redo, undo)

    private fun swapHistory(from: ArrayDeque<ShortArray>, to: ArrayDeque<ShortArray>) {
        val track = project?.lights ?: return
        val snapshot = from.removeLastOrNull() ?: return
        to.addLast(track.data.copyOf())
        snapshot.copyInto(track.data)
        changed()
    }

    private fun checkpoint() {
        val track = project?.lights ?: return
        undo.addLast(track.data.copyOf())
        if (undo.size > MAX_UNDO) undo.removeFirst()
        redo.clear()
    }

    private fun changed() {
        _state.update { it.copy(revision = it.revision + 1, canUndo = undo.isNotEmpty(), canRedo = redo.isNotEmpty()) }
        showFrame(_state.value.playhead)
        persist()
    }

    private var saveJob: Job? = null

    private fun persist() {
        val p = project ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(500)
            project = store.save(p, _state.value.name)
        }
    }

    fun rename(name: String) {
        _state.update { it.copy(name = name) }
        persist()
    }

    // endregion

    // region record

    override fun setOverdub(overdub: Boolean) = _state.update { it.copy(overdub = overdub) }
    override fun setPadBrightness(v: Float) = _state.update { it.copy(padBrightness = v) }

    override fun pressPad(pad: Pad) {
        if (project == null) return
        if (!_state.value.playing && performance.isIdle(jamFrame())) jamStartNanos = System.nanoTime()
        performance.press(pad, clockFrame())
        _state.update { it.copy(heldPads = performance.heldPads) }
        if (!_state.value.playing) startJam()
    }

    override fun releasePad(pad: Pad) {
        performance.release(pad, clockFrame())
        _state.update { it.copy(heldPads = performance.heldPads) }
    }

    /** Starts recording from the playhead (song playing), or stops and keeps the take. */
    override fun toggleRecord() {
        if (_state.value.recording) {
            finishRecording()
            return
        }
        if (project == null) return
        checkpoint()
        val s = _state.value
        val start = if (s.playhead >= s.frameCount - 1) 0 else s.playhead
        seek(start)
        performance.clear()
        lastRecorded = start - 1
        _state.update { it.copy(recording = true, selection = null) }
        play()
    }

    private fun finishRecording() {
        recordUpTo(_state.value.playhead)
        _state.update { it.copy(recording = false) }
        pause()
        changed()
    }

    /** Writes pad levels for every frame since the last write, on the song clock. */
    private fun recordUpTo(frame: Int) {
        val track = project?.lights ?: return
        val s = _state.value
        val peak = s.padBrightness * GlyphZones.MAX_BRIGHTNESS
        for (f in (lastRecorded + 1)..minOf(frame, track.frameCount - 1)) {
            performance.levelsAt(f, padLevels)
            for (z in 0 until GlyphZones.COUNT) {
                val v = (padLevels[z] * peak).toInt()
                track[f, z] = if (s.overdub) max(track[f, z], v) else v
            }
        }
        lastRecorded = max(lastRecorded, frame)
    }

    private fun clockFrame(): Int {
        val mp = player
        return if (_state.value.playing && mp != null) msToFrame(mp.currentPosition.toLong()) else jamFrame()
    }

    private fun jamFrame() = ((System.nanoTime() - jamStartNanos) * GlyphZones.FPS / 1_000_000_000L).toInt()

    /** While paused, animates held pads and fades on the lights until everything is idle. */
    private fun startJam() {
        if (jamJob?.isActive == true) return
        jamJob = viewModelScope.launch {
            while (isActive && !performance.isIdle(jamFrame())) {
                showLive(jamFrame(), _state.value.playhead)
                delay(16)
            }
            _state.update { it.copy(liveFrame = null, heldPads = performance.heldPads) }
            showFrame(_state.value.playhead)
        }
    }

    private fun stopJam() {
        jamJob?.cancel()
        performance.clear()
        _state.update { it.copy(liveFrame = null, heldPads = emptySet()) }
    }

    /** Shows the track at [trackFrame] with pad levels at [padFrame] on top, without saving. */
    private fun showLive(padFrame: Int, trackFrame: Int) {
        val track = project?.lights ?: return
        val peak = _state.value.padBrightness * GlyphZones.MAX_BRIGHTNESS
        performance.levelsAt(padFrame, padLevels)
        val frame = IntArray(GlyphZones.COUNT) { z ->
            max(track[trackFrame.coerceIn(0, track.frameCount - 1), z], (padLevels[z] * peak).toInt())
        }
        for (z in frame.indices) live[z] = frame[z].toShort()
        glyphApp.glyph.show(live)
        _state.update { it.copy(liveFrame = frame) }
    }

    // endregion

    // region export

    fun export(title: String) {
        val p = project ?: return
        pause()
        viewModelScope.launch {
            _state.update { it.copy(exportProgress = 0f, exportedUri = null) }
            try {
                rename(title)
                val uri = exporter.export(p, title) { pr -> _state.update { it.copy(exportProgress = pr) } }
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
        playbackJob?.cancel()
        jamJob?.cancel()
        player?.release()
        glyphApp.glyph.off()
        project?.let { p ->
            // viewModelScope is gone; a short blocking save keeps the last edit.
            runBlocking { store.save(p, _state.value.name) }
        }
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

    private companion object {
        const val MAX_UNDO = 30
    }
}
