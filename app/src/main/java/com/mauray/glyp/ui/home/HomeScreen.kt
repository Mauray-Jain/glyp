@file:OptIn(ExperimentalMaterial3Api::class)

package com.mauray.glyp.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
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
import com.mauray.glyp.ui.theme.DotDisplay
import com.mauray.glyp.ui.theme.Muted
import com.mauray.glyp.ui.theme.Surface1
import com.mauray.glyp.ui.theme.Surface2
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HomeScreen(onOpen: (String) -> Unit, vm: HomeViewModel = viewModel()) {
    val projects by vm.projects.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<ProjectMeta?>(null) }
    var choosingStart by remember { mutableStateOf(false) }
    var autoCompose by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) { vm.refresh() }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.create(uri, autoCompose, onOpen)
    }

    HomeLayout(
        projects = projects,
        busy = busy,
        onNew = { if (!busy) choosingStart = true },
        onOpen = onOpen,
        onDelete = { confirmDelete = it },
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

    confirmDelete?.let { p ->
        NothingDialog(
            title = "Delete ${p.name}?",
            body = "Ringtones you already exported stay on your phone.",
            confirm = "Delete",
            onConfirm = { vm.delete(p.id); confirmDelete = null },
            onDismiss = { confirmDelete = null },
        )
    }
    error?.let { msg ->
        NothingDialog(title = "Couldn't import", body = msg, confirm = "OK", onConfirm = vm::clearError, onDismiss = vm::clearError)
    }
}

@Composable
internal fun HomeLayout(
    projects: List<ProjectMeta>,
    busy: Boolean,
    onNew: () -> Unit,
    onOpen: (String) -> Unit,
    onDelete: (ProjectMeta) -> Unit,
) {
    Scaffold(containerColor = Color.Black) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 28.dp)) {
                    Text("Glyp", style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
                    CircleButton(Icons.Filled.Add, "New from song", onNew)
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
                item { EmptyState(onNew) }
            } else if (projects.isNotEmpty()) {
                item { SectionLabel("Compositions") }
                items(projects, key = { it.id }) { p ->
                    ProjectRow(p, onClick = { onOpen(p.id) }, onLongClick = { onDelete(p) })
                }
                item {
                    Text(
                        "HOLD TO DELETE",
                        style = MaterialTheme.typography.labelSmall,
                        color = Muted,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
            }
        }
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectRow(p: ProjectMeta, onClick: () -> Unit, onLongClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Surface1)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Dot-matrix monogram, like the account avatar in Nothing settings.
        Box(Modifier.size(52.dp).clip(CircleShape).background(Surface2), contentAlignment = Alignment.Center) {
            Text(monogram(p.name), style = DotDisplay.copy(fontSize = 22.sp))
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(p.name, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Spacer(Modifier.height(4.dp))
            Text(
                "%d:%02d  ·  %s".format(p.durationMs / 60_000, p.durationMs / 1000 % 60, dateFormat.format(Date(p.updatedAt)).uppercase()),
                style = MaterialTheme.typography.labelSmall,
                color = Muted,
            )
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Muted)
    }
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
