package com.mauray.glyphstudio.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauray.glyphstudio.ui.theme.Hairline
import com.mauray.glyphstudio.ui.theme.Muted
import com.mauray.glyphstudio.ui.theme.NothingRed
import com.mauray.glyphstudio.ui.theme.Surface1

/** Round grey icon button, as used across Nothing X. */
@Composable
fun CircleButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 52.dp,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = Surface1,
        contentColor = if (enabled) Color.White else Color.White.copy(alpha = 0.3f),
        modifier = modifier.size(size),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription, Modifier.size(22.dp)) }
    }
}

enum class PillStyle { RED, WHITE, GREY }

/** Full-height pill button; RED is reserved for the one main action on a screen. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: PillStyle = PillStyle.RED,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    height: Dp = 56.dp,
) {
    val (bg, fg) = when (style) {
        PillStyle.RED -> NothingRed to Color.White
        PillStyle.WHITE -> Color.White to Color.Black
        PillStyle.GREY -> Surface1 to Color.White
    }
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) bg else Surface1,
        contentColor = if (enabled) fg else Muted.copy(alpha = 0.6f),
        modifier = modifier.height(height),
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Small outlined toggle pill (the "SHM" pill in Glyph Composer). Selected = solid white. */
@Composable
fun TogglePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp),
) {
    Box(
        modifier
            .height(38.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(Color.White) else Modifier.border(BorderStroke(1.dp, Hairline), CircleShape))
            .clickable(onClick = onClick)
            .padding(contentPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
            textAlign = TextAlign.Center,
        )
    }
}

/** Rounded grey card. */
@Composable
fun NCard(
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val color = if (selected) Color.White else Surface1
    val contentColor = if (selected) Color.Black else Color.White
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = color, contentColor = contentColor, modifier = modifier, content = content)
    } else {
        Surface(shape = shape, color = color, contentColor = contentColor, modifier = modifier, content = content)
    }
}

/** Section caption above a group of cards, like "Nothing Account" in Settings. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = Color.White.copy(alpha = 0.9f), modifier = modifier.padding(start = 4.dp, bottom = 10.dp))
}

/**
 * Quick-settings style slider: a grey pill that fills with white. The label sits inside and
 * flips to black where the fill passes under it.
 */
@Composable
fun PillSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val fraction = ((value - range.start) / (range.endInclusive - range.start)).coerceIn(0f, 1f)
    val latestOnChange = rememberUpdatedState(onChange)
    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(CircleShape)
            .background(Surface1)
            .pointerInput(range) {
                fun set(x: Float) {
                    val f = (x / size.width).coerceIn(0f, 1f)
                    latestOnChange.value(range.start + f * (range.endInclusive - range.start))
                }
                detectTapGestures { set(it.x) }
            }
            .pointerInput(range) {
                detectHorizontalDragGestures { change, _ ->
                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                    latestOnChange.value(range.start + f * (range.endInclusive - range.start))
                }
            },
    ) {
        val full = maxWidth
        SliderLabels(label, valueText, Color.White, Muted, Modifier.requiredWidth(full))
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .clipToBounds()
                .background(Color.White),
            contentAlignment = Alignment.CenterStart,
        ) {
            // Same width as the track and pinned left, so both label layers line up exactly.
            SliderLabels(
                label, valueText, Color.Black, Color.Black.copy(alpha = 0.55f),
                Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(full),
            )
        }
    }
}

@Composable
private fun SliderLabels(label: String, value: String, labelColor: Color, valueColor: Color, modifier: Modifier) {
    Row(modifier.fillMaxHeight().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = labelColor, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.labelSmall, color = valueColor)
    }
}

/**
 * Pill-shaped segmented switch: grey track, the active segment is a white pill. Each segment
 * gets its label's natural width plus an equal share of the spare room, so long labels
 * ("Compose") never crowd the edges while short ones ("Edit") don't float in a huge pill.
 */
@Composable
fun SegmentedPills(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Layout(
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(Surface1)
            .padding(4.dp),
        content = {
            options.forEachIndexed { i, label ->
                val active = i == selected
                Box(
                    Modifier
                        .clip(CircleShape)
                        .then(if (active) Modifier.background(Color.White) else Modifier)
                        .clickable { onSelect(i) }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp),
                        color = if (active) Color.Black else Muted,
                        maxLines = 1,
                    )
                }
            }
        },
    ) { measurables, constraints ->
        val height = constraints.maxHeight
        val natural = measurables.map { it.maxIntrinsicWidth(height) }
        val total = natural.sum()
        val widths = if (total <= constraints.maxWidth) {
            val extra = (constraints.maxWidth - total) / measurables.size
            natural.map { it + extra }
        } else {
            // Too narrow: shrink proportionally rather than overflow the track.
            natural.map { it * constraints.maxWidth / total }
        }
        val placeables = measurables.mapIndexed { i, m -> m.measure(Constraints.fixed(widths[i], height)) }
        layout(constraints.maxWidth, height) {
            var x = 0
            placeables.forEach { it.place(x, 0); x += it.width }
        }
    }
}
