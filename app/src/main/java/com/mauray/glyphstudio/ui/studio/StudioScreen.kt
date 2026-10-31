@file:OptIn(ExperimentalLayoutApi::class)

package com.mauray.glyphstudio.ui.studio

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mauray.glyphstudio.composer.ComposerStyle
import com.mauray.glyphstudio.glyph.GlyphController
import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.model.LightFrames
import com.mauray.glyphstudio.model.frameToMs
import com.mauray.glyphstudio.studio.EditTool
import com.mauray.glyphstudio.ui.AppIcons
import com.mauray.glyphstudio.ui.components.CircleButton
import com.mauray.glyphstudio.ui.components.NCard
import com.mauray.glyphstudio.ui.components.PillButton
import com.mauray.glyphstudio.ui.components.PillSlider
import com.mauray.glyphstudio.ui.components.PillStyle
import com.mauray.glyphstudio.ui.components.SegmentedPills
import com.mauray.glyphstudio.ui.components.TogglePill
import com.mauray.glyphstudio.ui.theme.DotDisplay
import com.mauray.glyphstudio.ui.theme.dotClock
import com.mauray.glyphstudio.ui.theme.Muted
import com.mauray.glyphstudio.ui.theme.NothingRed
import com.mauray.glyphstudio.ui.theme.Surface1
import com.mauray.glyphstudio.ui.theme.Surface2

/**
 * Gives each opened project its own ViewModel that is cleared (player released, edits saved)
 * as soon as the user leaves the Studio, instead of living as long as the activity.
 */
@Composable
fun StudioScreen(projectId: String, onBack: () -> Unit) {
    val app = LocalContext.current.applicationContext as Application
    val owner = remember(projectId) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    val vm: StudioViewModel = viewModel(
        viewModelStoreOwner = owner,
        factory = ViewModelProvider.AndroidViewModelFactory.getInstance(app),
    )
    StudioScreen(projectId, onBack, vm)
}

@Composable
private fun StudioScreen(projectId: String, onBack: () -> Unit, vm: StudioViewModel) {
    LaunchedEffect(projectId) { vm.load(projectId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val glyphState by vm.glyphState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showExport by remember { mutableStateOf(false) }

    BackHandler { vm.pause(); onBack() }
    LaunchedEffect(s.message) {
        s.message?.let { snackbar.showSnackbar(it); vm.consumeMessage() }
    }

    StudioLayout(
        s = s,
        glyphState = glyphState,
        track = vm.document,
        waveform = vm.waveform,
        actions = vm,
        snackbar = snackbar,
        onBack = { vm.pause(); onBack() },
        onExport = { showExport = true },
    )

    if (showExport) {
        ExportDialog(vm, s, onDismiss = { showExport = false; vm.dismissExport() })
    }
}

/** The whole Studio screen as a pure function of its state; also used by screenshot tests. */
@Composable
internal fun StudioLayout(
    s: StudioUiState,
    glyphState: GlyphController.State,
    track: LightFrames?,
    waveform: FloatArray,
    actions: StudioActions,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onExport: () -> Unit,
    initialMode: StudioMode = StudioMode.COMPOSE,
) {
    var mode by rememberSaveable { mutableStateOf(initialMode) }
    val ready = s.loading == null && track != null

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (ready) TransportBar(s, actions) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, size = 48.dp)
                Spacer(Modifier.width(10.dp))
                if (ready) {
                    SegmentedPills(
                        options = StudioMode.entries.map { it.label },
                        selected = mode.ordinal,
                        onSelect = { mode = StudioMode.entries[it] },
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(10.dp))
                CircleButton(AppIcons.Upload, "Export ringtone", onExport, enabled = ready, size = 48.dp)
            }
            val loading = s.loading
            if (loading != null) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(loading, style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(20.dp))
                    LinearProgressIndicator(
                        progress = { s.progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color.White,
                        trackColor = Surface2,
                        gapSize = 0.dp,
                        drawStopIndicator = {},
                    )
                }
            } else if (track != null) {
                StudioHeader(s, glyphState)
                when (mode) {
                    // Record is one fixed screen: lights and pads visible together, no scrolling.
                    StudioMode.RECORD -> RecordLayout(track, actions, s, Modifier.weight(1f))
                    else -> StudioScroll(track, waveform, actions, s, mode)
                }
            }
        }
    }
}

enum class StudioMode(val label: String) { COMPOSE("Compose"), EDIT("Edit"), RECORD("Record") }

@Composable
private fun StudioHeader(s: StudioUiState, glyphState: GlyphController.State) {
    Column(Modifier.padding(horizontal = 24.dp).padding(top = 8.dp)) {
        Text(s.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(8.dp))
        // Big dot-matrix clock, total length and glyph status in small mono beside it.
        Row(verticalAlignment = Alignment.Bottom) {
            Text(dotClock(formatTime(s.playhead)), style = DotDisplay, color = Color.White)
            Spacer(Modifier.width(10.dp))
            Text(
                "/ ${formatTime(s.frameCount)}",
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
            Spacer(Modifier.weight(1f))
            val live = glyphState == GlyphController.State.READY
            Text(
                glyphStatus(glyphState),
                style = MaterialTheme.typography.labelSmall,
                color = if (live) NothingRed else Muted,
                modifier = Modifier.padding(bottom = 5.dp),
            )
        }
    }
}

/** Levels to show on the phone: live pad hits while jamming, otherwise the track at the playhead. */
internal fun previewLevels(track: LightFrames, s: StudioUiState): (Int) -> Int {
    val frame = s.playhead.coerceIn(0, track.frameCount - 1)
    val live = s.liveFrame
    return { zone -> live?.get(zone) ?: track[frame, zone] }
}

/** Compose and Edit: preview, timeline and the mode's controls in one scrolling column. */
@Composable
private fun StudioScroll(
    track: LightFrames,
    waveform: FloatArray,
    vm: StudioActions,
    s: StudioUiState,
    mode: StudioMode,
) {
    val timeline = remember { TimelineState() }
    val editing = mode == StudioMode.EDIT

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        GlyphPreview(
            levels = previewLevels(track, s),
            // Zone picking only means something in Edit.
            selected = if (editing) s.selectedZones else emptySet(),
            onZoneTap = { if (editing) vm.toggleZone(it) },
            modifier = Modifier.fillMaxWidth().height(PreviewHeight),
        )
        if (editing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                for (strip in GlyphZones.stripOrder) {
                    TogglePill(strip.displayName, s.selectedZones.containsAll(strip.zones.toList()), { vm.selectStrip(strip) })
                }
                TogglePill("All", s.selectedZones.size == GlyphZones.COUNT, { vm.selectStrip(null) })
            }
            Spacer(Modifier.height(20.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }

        NCard(Modifier.fillMaxWidth()) {
            Timeline(
                track = track,
                waveform = waveform,
                revision = s.revision,
                playhead = s.playhead,
                playing = s.playing,
                selection = s.selection,
                state = timeline,
                onSeek = vm::seek,
                onSelect = vm::setSelection,
                modifier = Modifier.fillMaxWidth().height(190.dp).padding(vertical = 14.dp),
            )
        }
        Text(
            "DRAG TO SELECT · PINCH TO ZOOM",
            style = MaterialTheme.typography.labelSmall,
            color = Muted.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 24.dp),
        )
        if (editing) EditPanel(vm, s) else ComposePanel(vm, s)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ComposePanel(vm: StudioActions, s: StudioUiState) {
    val p = s.composer
    // 2 x 2 grid of style cards.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in ComposerStyle.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (style in row) {
                    val selected = p.style == style
                    NCard(Modifier.weight(1f).height(96.dp), selected = selected, onClick = { vm.setComposer(p.copy(style = style)) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(style.label, style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                style.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (selected) Color.Black.copy(alpha = 0.6f) else Muted,
                            )
                        }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PillSlider("Sensitivity", p.sensitivity, 0f..1f, "${(p.sensitivity * 100).toInt()}%", { vm.setComposer(p.copy(sensitivity = it)) })
        PillSlider("Fade", p.releaseMs, 60f..800f, "${p.releaseMs.toInt()}MS", { vm.setComposer(p.copy(releaseMs = it)) })
        PillSlider("Brightness", p.intensity, 0.1f..1f, "${(p.intensity * 100).toInt()}%", { vm.setComposer(p.copy(intensity = it)) })
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Compose song", { vm.autoCompose(selectionOnly = false) }, Modifier.weight(1f), style = PillStyle.WHITE)
        PillButton("Selection", { vm.autoCompose(selectionOnly = true) }, Modifier.weight(1f), style = PillStyle.GREY, enabled = s.selection != null)
    }
}

/** Undo · Play · Redo, pinned to the bottom like the Nothing X action row. */
@Composable
private fun TransportBar(s: StudioUiState, actions: StudioActions) {
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(AppIcons.Undo, "Undo", actions::undo, enabled = s.canUndo, size = 60.dp)
        Spacer(Modifier.width(12.dp))
        PillButton(
            when {
                s.recording -> "Stop recording"
                s.playing -> "Pause"
                else -> "Play"
            },
            actions::togglePlay,
            Modifier.weight(1f),
            icon = when {
                s.recording -> AppIcons.Stop
                s.playing -> AppIcons.Pause
                else -> Icons.Filled.PlayArrow
            },
            height = 60.dp,
        )
        Spacer(Modifier.width(12.dp))
        CircleButton(AppIcons.Redo, "Redo", actions::redo, enabled = s.canRedo, size = 60.dp)
    }
}

@Composable
private fun ExportDialog(vm: StudioViewModel, s: StudioUiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(s.name) }
    val exporting = s.exportProgress != null
    val exported = s.exportedUri

    AlertDialog(
        onDismissRequest = { if (!exporting) onDismiss() },
        containerColor = Surface1,
        shape = RoundedCornerShape(28.dp),
        title = { Text(if (exported != null) "Saved" else "Export ringtone", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                when {
                    exported != null -> Text(
                        "Find it in Settings → Sound → Ringtone, or set it now and the lights play on your next call.",
                        color = Muted,
                    )
                    exporting -> {
                        Text("ENCODING", style = MaterialTheme.typography.labelSmall, color = Muted)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { s.exportProgress ?: 0f },
                            modifier = Modifier.fillMaxWidth(),
                            color = Color.White,
                            trackColor = Surface2,
                            gapSize = 0.dp,
                            drawStopIndicator = {},
                        )
                    }
                    else -> OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("Name") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, focusedLabelColor = Color.White, cursorColor = Color.White),
                    )
                }
            }
        },
        confirmButton = {
            when {
                exported != null -> PillButton("Set as ringtone", {
                    if (vm.canSetDefaultRingtone()) vm.setAsRingtone() else context.startActivity(vm.writeSettingsIntent())
                }, height = 48.dp)
                !exporting -> PillButton("Export", { vm.export(title.trim().ifEmpty { s.name }) }, height = 48.dp)
            }
        },
        dismissButton = {
            if (!exporting) PillButton(if (exported != null) "Done" else "Cancel", onDismiss, style = PillStyle.GREY, height = 48.dp)
        },
    )
}

private fun glyphStatus(state: GlyphController.State) = when (state) {
    GlyphController.State.READY -> "● LIVE"
    GlyphController.State.CONNECTING -> "CONNECTING"
    GlyphController.State.FAILED -> "GLYPH OFF"
    GlyphController.State.UNSUPPORTED -> "PREVIEW"
}

internal fun formatTime(frame: Int): String {
    val ms = frameToMs(frame)
    return "%d:%02d.%02d".format(ms / 60_000, ms / 1000 % 60, ms / 10 % 100)
}
