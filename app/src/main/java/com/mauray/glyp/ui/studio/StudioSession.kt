package com.mauray.glyp.ui.studio

import com.mauray.glyp.audio.Song
import com.mauray.glyp.composer.AutoComposer
import com.mauray.glyp.composer.ComposerParams
import com.mauray.glyp.export.ExportJob
import com.mauray.glyp.export.Ringtone
import com.mauray.glyp.glyph.GlyphZones
import com.mauray.glyp.model.LightDocument
import com.mauray.glyp.model.LightFrames
import com.mauray.glyp.model.ProjectMeta
import com.mauray.glyp.model.ProjectStore
import com.mauray.glyp.studio.Clock
import com.mauray.glyp.studio.EditOps
import com.mauray.glyp.studio.Pad
import com.mauray.glyp.studio.TakeRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/** What an open project is doing; decides which actions are allowed. */
sealed interface Phase {
    data object Idle : Phase
    data object Playing : Phase
    data object Recording : Phase
    data class Exporting(val progress: Float) : Phase

    /** Editing the lights (tools, compose, undo/redo, export, switching modes). */
    val canEdit: Boolean get() = this == Idle || this == Playing
}

/** The Studio screen's state: loading, failed, or an open project. */
sealed interface StudioState {
    data class Loading(val label: String, val progress: Float) : StudioState
    data class Failed(val message: String) : StudioState
    data class Open(
        val phase: Phase,
        val name: String,
        val track: LightFrames,
        /** Peak amplitude per light frame, 0..1. */
        val waveform: FloatArray,
        val playhead: Int,
        /** Stored values the phone shows right now: the track at the playhead with pads on top. */
        val lights: IntArray,
        val heldPads: Set<Pad>,
        /** Changes whenever the lights do, so the timeline redraws. */
        val revision: Int,
        val canUndo: Boolean,
        val canRedo: Boolean,
    ) : StudioState {
        val frameCount: Int get() = track.frameCount
    }
}

/**
 * The Studio for one open project: loads it, owns its light document and take recorder, and
 * runs the state machine
 *
 *     Loading -> Idle <-> Playing <-> Recording,  Idle/Playing -> Exporting -> Idle,  Loading -> Failed
 *
 * Every action is checked against the current [Phase]. Must be used from the main thread
 * (the thread [scope] runs on).
 */
class StudioSession(
    projectId: String,
    private val scope: CoroutineScope,
    private val store: ProjectStore,
    /** Decodes and analyses the song for [frames] light frames. */
    private val loadSong: suspend (audio: File, frames: Int, onProgress: (Float) -> Unit) -> Song,
    private val openClock: suspend (audio: File) -> Clock,
    private val exportRingtone: suspend (ExportJob, onProgress: (Float) -> Unit) -> Ringtone,
    /** Receives every frame the Glyphs should show. */
    private val showLights: (ShortArray) -> Unit,
    /** Where composing runs. */
    private val compute: CoroutineContext,
) : StudioActions {

    private class Opened(
        var meta: ProjectMeta,
        val audioFile: File,
        val song: Song,
        val document: LightDocument,
        val recorder: TakeRecorder,
    )

    private val _state = MutableStateFlow<StudioState>(StudioState.Loading("Loading…", 0f))
    val state: StateFlow<StudioState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)

    /** One-off notices for a snackbar. */
    val messages: Flow<String> = _messages.receiveAsFlow()

    private var opened: Opened? = null
    private var exporting: Phase.Exporting? = null

    /** Read from the sources, not from [state], which a collector updates a moment later. */
    private val phase: Phase? get() = opened?.let(::phaseOf)

    private fun phaseOf(o: Opened): Phase {
        val live = o.recorder.live.value
        return exporting ?: when {
            live.recording -> Phase.Recording
            live.playing -> Phase.Playing
            else -> Phase.Idle
        }
    }

    init {
        scope.launch { load(projectId) }
    }

    private suspend fun load(id: String) {
        try {
            val p = store.load(id)
            _state.value = StudioState.Loading("Listening to the track…", 0f)
            val song = loadSong(p.audioFile, p.lights.frameCount) { progress ->
                _state.value = StudioState.Loading("Listening to the track…", progress)
            }
            // A new project (unless started blank) gets its automatic composition once.
            val meta = if (p.meta.pendingAutoCompose) {
                withContext(compute) { AutoComposer.compose(song.analysis, ComposerParams(), p.lights) }
                store.save(p.meta.copy(pendingAutoCompose = false), p.lights)
            } else {
                p.meta
            }
            val document = LightDocument(p.lights)
            val recorder = TakeRecorder(document, openClock(p.audioFile), scope, showLights)
            val o = Opened(meta, p.audioFile, song, document, recorder)
            opened = o
            scope.launch { recorder.live.collect { publish(o) } }
            scope.launch { document.revision.collect { publish(o) } }
            // Debounced autosave: half a second after the last change, from a snapshot.
            scope.launch { document.revision.drop(1).collectLatest { delay(AUTOSAVE_DELAY_MS); save(o) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = StudioState.Failed("Couldn't open project: ${e.message}")
        }
    }

    private fun publish(o: Opened) {
        val live = o.recorder.live.value
        val doc = o.document
        _state.value = StudioState.Open(
            phase = phaseOf(o),
            name = o.meta.name,
            track = doc,
            waveform = o.song.waveform,
            playhead = live.playhead,
            lights = live.lights,
            heldPads = live.heldPads,
            revision = doc.revision.value,
            canUndo = doc.canUndo,
            canRedo = doc.canRedo,
        )
    }

    // region transport

    override fun togglePlay() {
        val o = opened ?: return
        when (phase) {
            Phase.Idle -> o.recorder.play()
            Phase.Playing, Phase.Recording -> o.recorder.pause()
            else -> Unit
        }
    }

    /** Pauses playback and commits a running take; used when leaving or backgrounding. */
    fun pause() {
        opened?.recorder?.pause()
    }

    override fun seek(frame: Int) {
        opened?.recorder?.seek(frame)
    }

    override fun toggleRecord(overdub: Boolean) {
        val o = opened ?: return
        when (phase) {
            Phase.Idle, Phase.Playing -> o.recorder.record(overdub)
            Phase.Recording -> o.recorder.pause()
            else -> Unit
        }
    }

    override fun pressPad(pad: Pad, level: Float) {
        opened?.recorder?.pressPad(pad, level)
    }

    override fun releasePad(pad: Pad) {
        opened?.recorder?.releasePad(pad)
    }

    // endregion

    // region editing

    override fun compose(params: ComposerParams, range: IntRange?) {
        val o = editable() ?: return
        val frames = range ?: 0 until o.document.frameCount
        scope.launch {
            o.document.editInBackground(compute) { AutoComposer.compose(o.song.analysis, params, it, frames) }
        }
    }

    override fun paint(request: PaintRequest) {
        val o = editable() ?: return
        val frames = request.frames
        when {
            frames == null -> notify("Drag across the timeline to pick a time range")
            request.zones.isEmpty() -> notify("Tap the lights or pick a strip first")
            else -> {
                // Sweeps travel in strip order C → A → B, each strip in its own zone order.
                val zones = GlyphZones.stripOrder.flatMap { strip -> strip.zones.filter { it in request.zones } }
                o.document.edit { EditOps.apply(it, request.tool, frames, zones, request.brightness, request.pulseRate) }
            }
        }
    }

    override fun undo() {
        editable()?.document?.undo()
    }

    override fun redo() {
        editable()?.document?.redo()
    }

    private fun editable(): Opened? = opened?.takeIf { phase?.canEdit == true }

    // endregion

    // region export

    /**
     * Saves the project as a ringtone named [title] (also renaming the project). Allowed from
     * Idle or Playing; playback stops first. Fails with a message on any error.
     */
    suspend fun export(title: String): Result<Ringtone> {
        val o = editable() ?: return Result.failure(IllegalStateException("Busy"))
        o.recorder.pause()
        o.meta = o.meta.copy(name = title)
        val job = ExportJob(title, o.audioFile, o.song.pcm, o.document.snapshot(), o.meta.durationMs)
        setExporting(o, Phase.Exporting(0f))
        return try {
            save(o)
            val ringtone = exportRingtone(job) { progress -> setExporting(o, Phase.Exporting(progress)) }
            notify("Saved to Ringtones/Glyp")
            Result.success(ringtone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            notify("Export failed: ${e.message}")
            Result.failure(e)
        } finally {
            setExporting(o, null)
        }
    }

    private fun setExporting(o: Opened, value: Phase.Exporting?) {
        exporting = value
        publish(o)
    }

    // endregion

    // region saving

    /** Writes the metadata and a snapshot of the lights. */
    private suspend fun save(o: Opened) {
        val doc = o.document
        val revision = doc.revision.value
        o.meta = store.save(o.meta, doc.snapshot())
        doc.markSaved(revision)
    }

    /** Commits any take and blocks for a last save; for when the scope is already gone. */
    fun close() {
        val o = opened ?: return
        o.recorder.close()
        if (o.document.dirty) runBlocking { save(o) }
    }

    // endregion

    private fun notify(message: String) {
        _messages.trySend(message)
    }

    private companion object {
        const val AUTOSAVE_DELAY_MS = 500L
    }
}
