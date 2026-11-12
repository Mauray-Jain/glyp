@file:OptIn(ExperimentalMaterial3Api::class)

package com.mauray.glyp.ui.home

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mauray.glyp.model.ProjectMeta
import com.mauray.glyp.ui.components.CircleButton
import com.mauray.glyp.ui.components.NCard
import com.mauray.glyp.ui.components.PillButton
import com.mauray.glyp.ui.components.PillStyle
import com.mauray.glyp.ui.components.SectionLabel
import com.mauray.glyp.ui.components.TogglePill
import com.mauray.glyp.ui.theme.DotDisplay
import com.mauray.glyp.ui.theme.Muted
import com.mauray.glyp.ui.theme.NothingRed
import com.mauray.glyp.ui.theme.Surface1
import com.mauray.glyp.ui.theme.Surface2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(onOpen: (String) -> Unit, vm: HomeViewModel = viewModel()) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var renaming by remember { mutableStateOf<ProjectMeta?>(null) }
    var choosingStart by remember { mutableStateOf(false) }
    var autoCompose by rememberSaveable { mutableStateOf(true) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.refresh() }
    // Projects that went away (deleted, or hidden for Undo) can't stay selected.
    LaunchedEffect(projects) { selected = selected.filterTo(mutableSetOf()) { id -> projects.any { it.id == id } } }
    BackHandler(enabled = selected.isNotEmpty()) { selected = emptySet() }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.create(uri, autoCompose, onOpen)
    }

    // Deletes are undoable rather than confirmed: hidden now, gone once the snackbar is.
    fun delete(ids: Set<String>) {
        if (ids.isEmpty()) return
        vm.delete(ids)
        selected = emptySet()
        snackbar.currentSnackbarData?.dismiss() // commits the previous delete
        scope.launch {
            var undo = false
            try {
                val what = if (ids.size == 1) "composition" else "${ids.size} compositions"
                undo = snackbar.showSnackbar("Deleted $what", actionLabel = "Undo", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed
            } finally {
                // Also runs when the user leaves the screen with the snackbar up.
                if (undo) vm.undoDelete(ids) else vm.commitDelete(ids)
            }
        }
    }

    HomeLayout(
        projects = projects,
        busy = busy,
        selected = selected,
        snackbar = snackbar,
        actions = HomeListActions(
            onNew = { if (!busy) choosingStart = true },
            onOpen = onOpen,
            onToggle = { id -> selected = if (id in selected) selected - id else selected + id },
            onSelectAll = { selected = if (selected.size == projects.size) emptySet() else projects.map { it.id }.toSet() },
            onClearSelection = { selected = emptySet() },
            onRename = { renaming = projects.firstOrNull { it.id in selected } },
            onDelete = ::delete,
        ),
    )

    if (choosingStart) {
        ModalBottomSheet(
            onDismissRequest = { choosingStart = false },
            containerColor = Color.Black,
            dragHandle = null,
            shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp),
        ) {
            StartChoice { auto ->
                autoCompose = auto
                choosingStart = false
                pickAudio.launch(arrayOf("audio/*"))
            }
        }
    }

    renaming?.let { p ->
        RenameDialog(
            name = p.name,
            onRename = { name ->
                vm.rename(p.id, name)
                renaming = null
                selected = emptySet()
            },
            onDismiss = { renaming = null },
        )
    }
    error?.let { msg ->
        NothingDialog(title = "Couldn't import", body = msg, confirm = "OK", onConfirm = vm::clearError, onDismiss = vm::clearError)
    }
}

/** What the Compositions list asks for; [HomeScreen] decides what each one does. */
internal class HomeListActions(
    val onNew: () -> Unit = {},
    val onOpen: (String) -> Unit = {},
    /** Long-press, or a tap while selecting: adds or removes a project from the selection. */
    val onToggle: (String) -> Unit = {},
    val onSelectAll: () -> Unit = {},
    val onClearSelection: () -> Unit = {},
    /** Renames the one selected project. */
    val onRename: () -> Unit = {},
    val onDelete: (Set<String>) -> Unit = {},
)

@Composable
internal fun HomeLayout(
    projects: List<ProjectMeta>,
    busy: Boolean,
    selected: Set<String>,
    snackbar: SnackbarHostState,
    actions: HomeListActions,
) {
    val selecting = selected.isNotEmpty()
    Scaffold(containerColor = Color.Black, snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                if (selecting) {
                    SelectionBar(selected, all = selected.size == projects.size, actions)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 28.dp)) {
                        Text("Glyp", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                        CircleButton(Icons.Filled.Add, "New from song", actions.onNew)
                    }
                }
                if (busy) {
                    LinearProgressIndicator(
                        Modifier.fillMaxWidth().padding(bottom = 20.dp),
                        color = Color.White,
                        trackColor = Surface2,
                        gapSize = 0.dp,
                    )
                }
            }
            if (projects.isEmpty() && !busy) {
                item { EmptyState(actions.onNew) }
            } else if (projects.isNotEmpty()) {
                item { SectionLabel("Compositions") }
                items(projects, key = { it.id }) { p ->
                    ProjectRow(
                        p,
                        selecting = selecting,
                        selected = p.id in selected,
                        onClick = { if (selecting) actions.onToggle(p.id) else actions.onOpen(p.id) },
                        onLongClick = { actions.onToggle(p.id) },
                        onDelete = { actions.onDelete(setOf(p.id)) },
                        modifier = Modifier.animateItem(),
                    )
                }
                item {
                    Text(
                        "SWIPE LEFT TO DELETE · HOLD TO SELECT",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
            }
        }
    }
}

/** Replaces the title while selecting: how many, select all, rename (one only), delete. */
@Composable
private fun SelectionBar(selected: Set<String>, all: Boolean, actions: HomeListActions) {
    val count = selected.size
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        // Same height as the title row, so the list doesn't jump when selection starts.
        modifier = Modifier.padding(bottom = 28.dp).height(52.dp),
    ) {
        CircleButton(Icons.Filled.Close, "Stop selecting", actions.onClearSelection, size = 48.dp)
        Text("$count selected", style = MaterialTheme.typography.titleLarge, maxLines = 1, modifier = Modifier.weight(1f).padding(start = 4.dp))
        TogglePill("All", all, actions.onSelectAll, contentPadding = PaddingValues(horizontal = 14.dp))
        CircleButton(Icons.Filled.Edit, "Rename", actions.onRename, enabled = count == 1, size = 48.dp)
        CircleButton(Icons.Filled.Delete, "Delete selected", { actions.onDelete(selected) }, size = 48.dp)
    }
}

/** "How do you want to start?" — auto-composed base or an empty track. */
@Composable
internal fun StartChoice(onChoose: (autoCompose: Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 28.dp, bottom = 32.dp)) {
        Text("How do you\nwant to start?", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(24.dp))
        StartOption("Auto compose", "Lights synced to the beat, ready to tweak.", "AUTO", onClick = { onChoose(true) })
        Spacer(Modifier.height(10.dp))
        StartOption("Start blank", "An empty track. Draw or record every light yourself.", "BLANK", onClick = { onChoose(false) })
        Spacer(Modifier.height(16.dp))
        Text(
            "NEXT: PICK A SONG",
            style = MaterialTheme.typography.labelSmall,
            color = Muted,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun StartOption(title: String, body: String, tag: String, onClick: () -> Unit) {
    NCard(Modifier.fillMaxWidth(), onClick = onClick) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(tag, style = MaterialTheme.typography.labelSmall, color = Muted)
                Spacer(Modifier.height(8.dp))
                Text(title, style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.height(4.dp))
                Text(body, style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Spacer(Modifier.width(12.dp))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
        }
    }
}

@Composable
private fun EmptyState(onNew: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("Turn any song\ninto light.", style = MaterialTheme.typography.headlineMedium, color = Muted, textAlign = TextAlign.Center)
        Spacer(Modifier.height(32.dp))
        PillButton("Pick a song", onNew, style = PillStyle.RED)
    }
}

/**
 * One composition. Swiping left deletes it (not while selecting); while selecting, a check
 * replaces the monogram on selected rows.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ProjectRow(
    p: ProjectMeta,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val swipe = rememberSwipeToDismissBoxState()
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        gesturesEnabled = !selecting,
        // Only a swipe to the left deletes; the other direction is disabled above.
        onDismiss = { value -> if (value == SwipeToDismissBoxValue.EndToStart) onDelete() },
        // While selecting, a sideways swipe would otherwise end as a tap and toggle the row.
        modifier = if (selecting) modifier.pointerInput(Unit) { detectHorizontalDragGestures { change, _ -> change.consume() } } else modifier,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(24.dp)).background(NothingRed).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, null, tint = Color.White)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(if (selected) Surface2 else Surface1)
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                    onClickLabel = if (selecting) (if (selected) "Unselect" else "Select") else "Open",
                    onLongClickLabel = if (selected) "Unselect" else "Select",
                )
                // The swipe has no TalkBack equivalent of its own.
                .semantics {
                    this.selected = selected
                    if (!selecting) customActions = listOf(CustomAccessibilityAction("Delete") { onDelete(); true })
                }
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selected) {
                Box(Modifier.size(52.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Check, null, tint = Color.Black)
                }
            } else {
                // Dot-matrix monogram, like the account avatar in Nothing settings.
                Box(Modifier.size(52.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
                    Text(monogram(p.name), style = DotDisplay.copy(fontSize = 22.sp))
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    "%d:%02d  ·  %s".format(p.durationMs / 60_000, p.durationMs / 1000 % 60, dateFormat.format(Date(p.updatedAt)).uppercase()),
                    style = MaterialTheme.typography.labelSmall,
                    color = Muted,
                )
            }
            if (!selecting) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
        }
    }
}

@Composable
private fun RenameDialog(name: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    // Opens focused with the whole name selected, so typing replaces it.
    var field by remember { mutableStateOf(TextFieldValue(name, TextRange(0, name.length))) }
    val text = field.text
    val focus = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        shape = RoundedCornerShape(28.dp),
        title = { Text("Rename", style = MaterialTheme.typography.headlineMedium) },
        text = {
            // Inside the dialog, and only once its window has focus: asked any earlier, the
            // keyboard doesn't open.
            val window = LocalWindowInfo.current
            val keyboard = LocalSoftwareKeyboardController.current
            LaunchedEffect(Unit) {
                snapshotFlow { window.isWindowFocused }.first { it }
                focus.requestFocus()
                keyboard?.show()
            }
            OutlinedTextField(
                value = field,
                onValueChange = { field = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.focusRequester(focus),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, focusedLabelColor = Color.White, cursorColor = Color.White),
            )
        },
        confirmButton = {
            PillButton("Rename", { onRename(text.trim()) }, height = 48.dp, enabled = text.isNotBlank() && text.trim() != name)
        },
        dismissButton = { PillButton("Cancel", onDismiss, style = PillStyle.GREY, height = 48.dp) },
    )
}

@Composable
private fun NothingDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface1,
        shape = RoundedCornerShape(28.dp),
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = { Text(body, color = Muted) },
        confirmButton = { PillButton(confirm, onConfirm, height = 48.dp) },
        dismissButton = if (confirm == "OK") null else {
            { PillButton("Cancel", onDismiss, style = PillStyle.GREY, height = 48.dp) }
        },
    )
}

private val dateFormat = SimpleDateFormat("MMM d", Locale.getDefault())

private fun monogram(name: String): String =
    name.split(' ', '-', '_')
        .mapNotNull { word -> word.firstOrNull { it.isLetterOrDigit() } }
        .take(2)
        .joinToString("") { it.uppercase() }
        .ifEmpty { "♪" }
