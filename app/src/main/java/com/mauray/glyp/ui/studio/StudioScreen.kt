package com.mauray.glyp.ui.studio

import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mauray.glyp.audio.Timebase
import com.mauray.glyp.composer.ComposerStyle
import com.mauray.glyp.export.Ringtone
import com.mauray.glyp.glyph.GlyphController
import com.mauray.glyp.glyph.GlyphZones
import com.mauray.glyp.ui.AppIcons
import com.mauray.glyp.ui.components.CircleButton
import com.mauray.glyp.ui.components.NCard
import com.mauray.glyp.ui.components.PillButton
import com.mauray.glyp.ui.components.PillSlider
import com.mauray.glyp.ui.components.PillStyle
import com.mauray.glyp.ui.components.SegmentedPills
import com.mauray.glyp.ui.components.TogglePill
import com.mauray.glyp.ui.theme.DotDisplay
import com.mauray.glyp.ui.theme.dotClock
import com.mauray.glyp.ui.theme.Muted
import com.mauray.glyp.ui.theme.NothingRed
import com.mauray.glyp.ui.theme.Surface1
import com.mauray.glyp.ui.theme.Surface2
import kotlinx.coroutines.launch

/**
 * Gives each opened project its own ViewModel that is cleared (take committed, edits saved)
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
    val vm = viewModel(viewModelStoreOwner = owner) { StudioViewModel(app, projectId) }
    val session = vm.session
    val s by session.state.collectAsStateWithLifecycle()
    val glyphState by vm.glyphState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var export by remember { mutableStateOf<ExportStage>(ExportStage.Closed) }

    BackHandler { session.pause(); onBack() }
    // Nothing plays in the background; pausing also commits a running take.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { session.pause() }
    LaunchedEffect(session) { session.messages.collect { snackbar.showSnackbar(it) } }

    StudioLayout(
        s = s,
        controls = vm.controls,
        glyphState = glyphState,
        actions = session,
        snackbar = snackbar,
        onBack = { session.pause(); onBack() },
        onExport = { export = ExportStage.Form },
    )

    val open = s as? StudioState.Open
    if (open != null && export != ExportStage.Closed) {
        ExportDialog(
            stage = export,
            phase = open.phase,
            name = open.name,
            onExport = { title ->
                export = ExportStage.Encoding
                scope.launch {
                    export = session.export(title).fold({ ExportStage.Saved(it) }, { ExportStage.Form })
                }
            },
            onSetRingtone = { ringtone ->
                val message = if (ringtone.setAsDefault()) "Set as your ringtone" else "Allow \"Modify system settings\", then tap again"
                scope.launch { snackbar.showSnackbar(message) }
            },
            onDismiss = { export = ExportStage.Closed },
        )
    }
}

/** The whole Studio screen as a pure function of its state; also used by screenshot tests. */
@Composable
internal fun StudioLayout(
    s: StudioState,
    controls: StudioControls,
    glyphState: GlyphController.State,
    actions: StudioActions,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    val open = s as? StudioState.Open
    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (open != null) TransportBar(open, actions) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleButton(Icons.AutoMirrored.Filled.ArrowBack, "Back", onBack, size = 48.dp)
                Spacer(Modifier.width(10.dp))
                if (open != null) {
                    SegmentedPills(
                        options = StudioMode.entries.map { it.label },
                        selected = controls.mode.ordinal,
                        onSelect = { controls.mode = StudioMode.entries[it] },
                        modifier = Modifier.weight(1f),
                        enabled = open.phase.canEdit,
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(10.dp))
                CircleButton(AppIcons.Upload, "Export ringtone", onExport, enabled = open?.phase?.canEdit == true, size = 48.dp)
            }
            when (s) {
                is StudioState.Loading -> StatusBody(s.label) {
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
                is StudioState.Failed -> StatusBody(s.message) {}
                is StudioState.Open -> {
                    StudioHeader(s, glyphState)
                    when (controls.mode) {
                        // Record is one fixed screen: lights and pads visible together, no scrolling.
                        StudioMode.RECORD -> RecordLayout(actions, s, controls, Modifier.weight(1f))
                        else -> StudioScroll(s, controls, actions)
                    }
                }
            }
        }
    }
}

/** A headline in the middle of the screen, for loading and errors. */
@Composable
private fun StatusBody(headline: String, below: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center) {
        Text(headline, style = MaterialTheme.typography.headlineMedium)
        below()
    }
}

@Composable
private fun StudioHeader(s: StudioState.Open, glyphState: GlyphController.State) {
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

/** Compose and Edit: preview, timeline and the mode's controls in one scrolling column. */
@Composable
private fun StudioScroll(s: StudioState.Open, controls: StudioControls, actions: StudioActions) {
    val timeline = remember { TimelineState() }
    val editing = controls.mode == StudioMode.EDIT

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
        GlyphPreview(
            levels = s.lights::get,
            // Zone picking only means something in Edit.
            selected = if (editing) controls.selectedZones else emptySet(),
            onZoneTap = { if (editing) controls.toggleZone(it) },
            modifier = Modifier.fillMaxWidth().height(PreviewHeight),
        )
        if (editing) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                for (strip in GlyphZones.stripOrder) {
                    TogglePill(strip.displayName, controls.selectedZones.containsAll(strip.zones.toList()), { controls.toggleStrip(strip) })
                }
                TogglePill("All", controls.selectedZones.size == GlyphZones.COUNT, { controls.toggleStrip(null) })
            }
            Spacer(Modifier.height(20.dp))
        } else {
            Spacer(Modifier.height(8.dp))
        }

        NCard(Modifier.fillMaxWidth()) {
            Timeline(
                track = s.track,
                waveform = s.waveform,
                revision = s.revision,
                playhead = s.playhead,
                playing = s.phase == Phase.Playing,
                selection = controls.selection,
                state = timeline,
                onSeek = actions::seek,
                onSelect = controls::select,
                modifier = Modifier.fillMaxWidth().height(190.dp).padding(vertical = 14.dp),
            )
        }
        Text(
            "DRAG TO SELECT · PINCH TO ZOOM",
            style = MaterialTheme.typography.labelSmall,
            color = Muted.copy(alpha = 0.6f),
            modifier = Modifier.padding(top = 10.dp, start = 4.dp, bottom = 24.dp),
        )
        if (editing) EditPanel(actions, controls) else ComposePanel(actions, controls)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun ComposePanel(actions: StudioActions, controls: StudioControls) {
    val p = controls.composer
    // 2 x 2 grid of style cards.
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in ComposerStyle.entries.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (style in row) {
                    val selected = p.style == style
                    NCard(Modifier.weight(1f).height(96.dp), selected = selected, onClick = { controls.composer = p.copy(style = style) }) {
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
        PillSlider("Sensitivity", p.sensitivity, 0f..1f, "${(p.sensitivity * 100).toInt()}%", { controls.composer = p.copy(sensitivity = it) })
        PillSlider("Fade", p.releaseMs, 60f..800f, "${p.releaseMs.toInt()}MS", { controls.composer = p.copy(releaseMs = it) })
        PillSlider("Brightness", p.intensity, 0.1f..1f, "${(p.intensity * 100).toInt()}%", { controls.composer = p.copy(intensity = it) })
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Compose song", { actions.compose(p, null) }, Modifier.weight(1f), style = PillStyle.WHITE)
        val selection = controls.selection
        PillButton(
            "Selection",
            { if (selection != null) actions.compose(p, selection) },
            Modifier.weight(1f),
            style = PillStyle.GREY,
            enabled = selection != null,
        )
    }
}

/** Undo · Play · Redo, pinned to the bottom like the Nothing X action row. */
@Composable
private fun TransportBar(s: StudioState.Open, actions: StudioActions) {
    val editable = s.phase.canEdit
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircleButton(AppIcons.Undo, "Undo", actions::undo, enabled = editable && s.canUndo, size = 60.dp)
        Spacer(Modifier.width(12.dp))
        PillButton(
            when (s.phase) {
                Phase.Recording -> "Stop recording"
                Phase.Playing -> "Pause"
                else -> "Play"
            },
            actions::togglePlay,
            Modifier.weight(1f),
            enabled = s.phase !is Phase.Exporting,
            icon = when (s.phase) {
                Phase.Recording -> AppIcons.Stop
                Phase.Playing -> AppIcons.Pause
                else -> Icons.Filled.PlayArrow
            },
            height = 60.dp,
        )
        Spacer(Modifier.width(12.dp))
        CircleButton(AppIcons.Redo, "Redo", actions::redo, enabled = editable && s.canRedo, size = 60.dp)
    }
}

/** Where the export dialog is; encoding progress itself comes from [Phase.Exporting]. */
private sealed interface ExportStage {
    data object Closed : ExportStage
    data object Form : ExportStage
    data object Encoding : ExportStage
    data class Saved(val ringtone: Ringtone) : ExportStage
}

@Composable
private fun ExportDialog(
    stage: ExportStage,
    phase: Phase,
    name: String,
    onExport: (title: String) -> Unit,
    onSetRingtone: (Ringtone) -> Unit,
    onDismiss: () -> Unit,
) {
    var title by remember { mutableStateOf(name) }
    val encoding = stage == ExportStage.Encoding

    AlertDialog(
        onDismissRequest = { if (!encoding) onDismiss() },
        containerColor = Surface1,
        shape = RoundedCornerShape(28.dp),
        title = { Text(if (stage is ExportStage.Saved) "Saved" else "Export ringtone", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                when (stage) {
                    is ExportStage.Saved -> Text(
                        "Find it in Settings → Sound → Ringtone, or set it now and the lights play on your next call.",
                        color = Muted,
                    )
                    ExportStage.Encoding -> {
                        Text("ENCODING", style = MaterialTheme.typography.labelSmall, color = Muted)
                        Spacer(Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { (phase as? Phase.Exporting)?.progress ?: 0f },
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
            when (stage) {
                is ExportStage.Saved -> PillButton("Set as ringtone", { onSetRingtone(stage.ringtone) }, height = 48.dp)
                ExportStage.Encoding -> Unit
                else -> PillButton("Export", { onExport(title.trim().ifEmpty { name }) }, height = 48.dp)
            }
        },
        dismissButton = {
            if (!encoding) PillButton(if (stage is ExportStage.Saved) "Done" else "Cancel", onDismiss, style = PillStyle.GREY, height = 48.dp)
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
    val ms = Timebase.msAt(frame)
    return "%d:%02d.%02d".format(ms / 60_000, ms / 1000 % 60, ms / 10 % 100)
}
