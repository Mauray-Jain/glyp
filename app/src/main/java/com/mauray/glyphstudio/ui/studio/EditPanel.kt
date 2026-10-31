package com.mauray.glyphstudio.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.studio.EditTool
import com.mauray.glyphstudio.ui.components.NCard
import com.mauray.glyphstudio.ui.components.PillButton
import com.mauray.glyphstudio.ui.components.PillSlider
import com.mauray.glyphstudio.ui.components.PillStyle
import com.mauray.glyphstudio.ui.theme.Muted
import kotlin.math.PI
import kotlin.math.cos

/** Shape tools shown as cards; Clear is a separate button. */
private val paintTools = listOf(
    EditTool.FILL, EditTool.FADE_IN, EditTool.FADE_OUT,
    EditTool.PULSE, EditTool.SWEEP, EditTool.SWEEP_BACK,
)

/**
 * Edit: pick a shape, see what it will apply to (time range + lights), then apply it.
 */
@Composable
internal fun EditPanel(actions: StudioActions, controls: StudioControls) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (row in paintTools.chunked(3)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (tool in row) {
                    val selected = controls.tool == tool
                    NCard(Modifier.weight(1f).height(92.dp), selected = selected, onClick = { controls.tool = tool }) {
                        Column(Modifier.padding(14.dp)) {
                            val ink = if (selected) Color.Black else Color.White
                            Canvas(Modifier.size(width = 56.dp, height = 26.dp)) { drawToolShape(tool, ink) }
                            Spacer(Modifier.weight(1f))
                            Text(tool.label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    Spacer(Modifier.height(20.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        PillSlider("Brightness", controls.brightness, 0.05f..1f, "${(controls.brightness * 100).toInt()}%", { controls.brightness = it })
        if (controls.tool == EditTool.PULSE) {
            PillSlider("Speed", controls.pulseRate, 0.5f..8f, "%.1f/S".format(controls.pulseRate), { controls.pulseRate = it })
        }
    }

    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        val range = controls.selection
        TargetCard(
            "RANGE",
            if (range != null) "${formatTime(range.first)} – ${formatTime(range.last + 1)}" else null,
            "Drag the timeline",
            Modifier.weight(1f),
        )
        TargetCard("LIGHTS", describeZones(controls.selectedZones), "Tap the lights", Modifier.weight(1f))
    }

    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PillButton("Apply ${controls.tool.label.lowercase()}", { actions.paint(controls.paint()) }, Modifier.weight(1.7f), style = PillStyle.WHITE)
        PillButton("Clear", { actions.paint(controls.paint(EditTool.CLEAR)) }, Modifier.weight(1f), style = PillStyle.GREY)
    }
}

/** What the tool will act on; shows a muted hint until it's been picked. */
@Composable
private fun TargetCard(label: String, value: String?, hint: String, modifier: Modifier) {
    NCard(modifier.height(84.dp)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = Muted)
            Spacer(Modifier.weight(1f))
            Text(
                value ?: hint,
                style = MaterialTheme.typography.titleSmall,
                color = if (value != null) Color.White else Muted.copy(alpha = 0.7f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "Top", "Top + Right", "All", or a count when the selection isn't whole strips. */
private fun describeZones(zones: Set<Int>): String? {
    if (zones.isEmpty()) return null
    if (zones.size == GlyphZones.COUNT) return "All lights"
    val strips = GlyphZones.stripOrder.filter { strip -> strip.zones.all { it in zones } }
    val wholeStrips = strips.sumOf { it.zones.count() } == zones.size
    return if (wholeStrips) strips.joinToString(" + ") { it.displayName } else "${zones.size} lights"
}

/** A tiny brightness-over-time sketch of what each tool does. */
private fun DrawScope.drawToolShape(tool: EditTool, ink: Color) {
    val w = size.width
    val h = size.height
    val stroke = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    val top = h * 0.12f
    val bottom = h * 0.88f
    when (tool) {
        EditTool.FILL -> drawLine(ink, Offset(0f, top), Offset(w, top), stroke.width, StrokeCap.Round)
        EditTool.FADE_IN -> drawLine(ink, Offset(0f, bottom), Offset(w, top), stroke.width, StrokeCap.Round)
        EditTool.FADE_OUT -> drawLine(ink, Offset(0f, top), Offset(w, bottom), stroke.width, StrokeCap.Round)
        EditTool.PULSE -> {
            val path = Path()
            val steps = 40
            for (i in 0..steps) {
                val x = w * i / steps
                val y = bottom - (bottom - top) * (0.5f - 0.5f * cos(2 * PI * 2 * i / steps).toFloat())
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, ink, style = stroke)
        }
        EditTool.SWEEP, EditTool.SWEEP_BACK -> {
            // A row of dots brightening toward the head of the sweep.
            val dots = 7
            for (i in 0 until dots) {
                val t = i.toFloat() / (dots - 1)
                val strength = if (tool == EditTool.SWEEP) t else 1 - t
                val r = 2.dp.toPx() + 2.dp.toPx() * strength
                drawCircle(ink.copy(alpha = 0.2f + 0.8f * strength), r, Offset(w * t * 0.9f + r, h / 2))
            }
        }
        EditTool.CLEAR -> drawLine(ink.copy(alpha = 0.4f), Offset(0f, bottom), Offset(w, bottom), stroke.width, StrokeCap.Round)
    }
}
