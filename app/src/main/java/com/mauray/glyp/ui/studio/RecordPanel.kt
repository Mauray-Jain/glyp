package com.mauray.glyp.ui.studio

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mauray.glyp.studio.Pad
import com.mauray.glyp.ui.components.PillSlider
import com.mauray.glyp.ui.components.TogglePill
import com.mauray.glyp.ui.theme.Muted
import com.mauray.glyp.ui.theme.NothingRed
import com.mauray.glyp.ui.theme.Surface1

/**
 * Record mode as one fixed screen: the phone's lights on top, the take's progress, the record
 * controls and the pads underneath, so you can watch the Glyphs while you play them.
 * Hold pads to light the Glyphs live; with ● armed the song plays from the playhead and
 * every hit is written into the track.
 */
@Composable
internal fun RecordLayout(actions: StudioActions, s: StudioState.Open, controls: StudioControls, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        GlyphPreview(
            levels = s.lights::get,
            selected = emptySet(),
            onZoneTap = {},
            // Same size and position as in Compose/Edit; spare height goes below instead.
            modifier = Modifier.fillMaxWidth().height(PreviewHeight),
        )
        Spacer(Modifier.weight(1f))
        val recording = s.phase == Phase.Recording
        TakeProgress(s.playhead, s.frameCount, recording)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            RecordButton(recording) {
                controls.select(null)
                actions.toggleRecord(controls.overdub)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (recording) "● REC" else "READY",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (recording) NothingRed else Muted,
                )
                Spacer(Modifier.height(2.dp))
                Text(if (recording) "Tap to stop" else "Tap to record", style = MaterialTheme.typography.bodySmall, color = Muted)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TogglePill("Layer", controls.overdub, { controls.overdub = true })
                TogglePill("Replace", !controls.overdub, { controls.overdub = false })
            }
        }
        Spacer(Modifier.height(12.dp))
        PillSlider("Pad brightness", controls.padBrightness, 0.1f..1f, "${(controls.padBrightness * 100).toInt()}%", { controls.padBrightness = it })
        Spacer(Modifier.height(12.dp))
        PadGrid(s.heldPads, { actions.pressPad(it, controls.padBrightness) }, actions::releasePad)
        Spacer(Modifier.height(8.dp))
    }
}

/** Thin playhead line under the phone; red while a take is running. */
@Composable
private fun TakeProgress(playhead: Int, frameCount: Int, recording: Boolean) {
    val fraction = if (frameCount > 1) playhead.toFloat() / (frameCount - 1) else 0f
    Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Surface1)) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .fillMaxHeight()
                .clip(CircleShape)
                .background(if (recording) NothingRed else Color.White),
        )
    }
}

/** Red dot when idle; red ring with a stop square while recording. */
@Composable
private fun RecordButton(recording: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Surface1)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (recording) "Stop recording" else "Record" },
        contentAlignment = Alignment.Center,
    ) {
        if (recording) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(5.dp)).background(NothingRed))
        } else {
            Box(Modifier.size(28.dp).clip(CircleShape).background(NothingRed))
        }
    }
}

/** Five pads in the Glyph Composer arrangement: circle/square, tall pill, square/circle. */
@Composable
private fun PadGrid(held: Set<Pad>, onPress: (Pad) -> Unit, onRelease: (Pad) -> Unit) {
    val gap = 10.dp
    Row(Modifier.fillMaxWidth().height(228.dp), horizontalArrangement = Arrangement.spacedBy(gap)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
            PadButton(Pad.TOP, CircleShape, held, onPress, onRelease, Modifier.weight(1f))
            PadButton(Pad.BOTTOM, RoundedCornerShape(28.dp), held, onPress, onRelease, Modifier.weight(1f))
        }
        PadButton(Pad.ALL, CircleShape, held, onPress, onRelease, Modifier.weight(1f).fillMaxHeight())
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
            PadButton(Pad.RIGHT, RoundedCornerShape(28.dp), held, onPress, onRelease, Modifier.weight(1f))
            PadButton(Pad.SWEEP, CircleShape, held, onPress, onRelease, Modifier.weight(1f))
        }
    }
}

@Composable
private fun PadButton(
    pad: Pad,
    shape: Shape,
    held: Set<Pad>,
    onPress: (Pad) -> Unit,
    onRelease: (Pad) -> Unit,
    modifier: Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val down = pad in held
    val squareish = pad != Pad.ALL
    Box(
        modifier
            .then(if (squareish) Modifier.aspectRatio(1f, matchHeightConstraintsFirst = true) else Modifier)
            .clip(shape)
            .background(if (down) Color.White else Surface1)
            .semantics { role = Role.Button; contentDescription = "${pad.label} pad" }
            .pointerInput(pad) {
                awaitEachGesture {
                    awaitFirstDown().consume()
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    onPress(pad)
                    // Swallow moves so the page doesn't scroll while a pad is held.
                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach { it.consume() }
                    } while (event.changes.any { it.pressed })
                    onRelease(pad)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (down) Color.Black else Color.Black.copy(alpha = 0.85f)))
        Text(
            pad.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (down) Color.Black.copy(alpha = 0.6f) else Muted,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}
