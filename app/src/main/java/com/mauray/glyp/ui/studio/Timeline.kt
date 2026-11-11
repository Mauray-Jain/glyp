package com.mauray.glyp.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mauray.glyp.glyph.GlyphZones
import com.mauray.glyp.model.LightFrames
import com.mauray.glyp.ui.theme.Mono
import com.mauray.glyp.ui.theme.Muted
import com.mauray.glyp.ui.theme.NothingRed
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Stable
class TimelineState {
    /** Pixels per light frame. */
    var zoom by mutableFloatStateOf(3f)
    /** First visible frame (fractional). */
    var scroll by mutableFloatStateOf(0f)
    var width by mutableIntStateOf(0)

    fun frameAt(x: Float) = (scroll + x / zoom).toInt()
    fun xOf(frame: Int) = (frame - scroll) * zoom
    val visibleFrames get() = width / zoom

    fun clamp(frameCount: Int) {
        zoom = zoom.coerceIn(0.05f, 24f)
        scroll = scroll.coerceIn(0f, max(0f, frameCount - visibleFrames))
    }
}

/**
 * Waveform on top, then one row per zone (C, A, B groups) showing brightness over time.
 * One finger drags out a selection, a tap moves the playhead (keeping the selection), two
 * fingers pan and zoom.
 */
@Composable
fun Timeline(
    track: LightFrames,
    waveform: FloatArray,
    revision: Int,
    playhead: Int,
    playing: Boolean,
    selection: IntRange?,
    state: TimelineState,
    onSeek: (Int) -> Unit,
    onSelect: (IntRange?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val labels = rememberTextMeasurer()

    // Keep the playhead on screen while playing by paging the view.
    LaunchedEffect(playhead, playing) {
        if (playing && state.width > 0) {
            val x = state.xOf(playhead)
            if (x < 0 || x > state.width * 0.9f) {
                state.scroll = playhead - state.visibleFrames * 0.1f
                state.clamp(track.frameCount)
            }
        }
    }

    Row(modifier) {
    // Strip names in their own gutter so they never cover the light data.
    Canvas(Modifier.width(52.dp).fillMaxHeight()) { drawLaneLabels(labels) }
    Box(
        Modifier
            .weight(1f)
            .fillMaxHeight()
            .clipToBounds()
            .onSizeChanged { state.width = it.width; state.clamp(track.frameCount) }
            .pointerInput(track) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val startX = down.position.x
                    val anchor = state.frameAt(startX)
                    var dragging = false
                    var zooming = false
                    // A mostly vertical move is the page scrolling: neither a tap nor a selection.
                    var scrolling = false
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            zooming = true
                            val zoom = event.calculateZoom()
                            val pan = event.calculatePan()
                            val centroid = event.calculateCentroid()
                            val focus = state.scroll + (centroid.x - pan.x) / state.zoom
                            state.zoom = (state.zoom * zoom).coerceIn(0.05f, 24f)
                            state.scroll = focus - centroid.x / state.zoom
                            state.clamp(track.frameCount)
                            event.changes.forEach { it.consume() }
                        } else if (!zooming && !scrolling && pressed.size == 1) {
                            val change = pressed[0]
                            if (!dragging) {
                                val dx = abs(change.position.x - startX)
                                val dy = abs(change.position.y - down.position.y)
                                val slop = viewConfiguration.touchSlop
                                if (dy > slop && dy > dx) scrolling = true else if (dx > slop) dragging = true
                            }
                            if (dragging) {
                                val current = state.frameAt(change.position.x).coerceIn(0, track.frameCount - 1)
                                onSelect(min(anchor, current)..max(anchor, current))
                                change.consume()
                            }
                        }
                    } while (event.changes.any { it.pressed })
                    // A tap only moves the playhead, so it can be put inside a selection.
                    if (!dragging && !zooming && !scrolling) onSeek(state.frameAt(startX))
                }
            },
    ) {
        // Heavy layer: only redraws when the data (revision), zoom or scroll change.
        key(revision) {
            Canvas(Modifier.fillMaxSize()) { drawLanes(track, waveform, state) }
        }
        // Light layer: playhead and selection, redrawn every frame during playback.
        Canvas(Modifier.fillMaxSize()) {
            selection?.let {
                val x0 = state.xOf(it.first)
                val x1 = state.xOf(it.last + 1)
                drawRect(NothingRed.copy(alpha = 0.16f), Offset(x0, 0f), Size(x1 - x0, size.height))
                drawLine(NothingRed, Offset(x0, 0f), Offset(x0, size.height), 1.dp.toPx())
                drawLine(NothingRed, Offset(x1, 0f), Offset(x1, size.height), 1.dp.toPx())
            }
            val px = state.xOf(playhead)
            drawLine(Color.White, Offset(px, 0f), Offset(px, size.height), 2.dp.toPx())
        }
    }
    }
}

/** Vertical layout shared by the lanes and their labels: waveform band, then one band per strip. */
private class LaneLayout(height: Float, groupGap: Float) {
    val waveHeight = height * 0.22f
    val rowHeight = (height - waveHeight - groupGap * GlyphZones.stripOrder.size) / GlyphZones.COUNT
    val tops: List<Float> = GlyphZones.stripOrder.runningFold(waveHeight + groupGap) { top, strip ->
        top + rowHeight * strip.zones.count() + groupGap
    }.dropLast(1)

    fun height(strip: GlyphZones.Strip) = rowHeight * strip.zones.count()
}

private fun DrawScope.drawLaneLabels(labels: TextMeasurer) {
    val layout = LaneLayout(size.height, 6.dp.toPx())
    val style = TextStyle(color = Muted, fontSize = 9.sp, fontFamily = Mono, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp)
    GlyphZones.stripOrder.forEachIndexed { i, strip ->
        val label = labels.measure(strip.displayName.uppercase(), style)
        drawText(label, topLeft = Offset(14.dp.toPx(), layout.tops[i] + (layout.height(strip) - label.size.height) / 2))
    }
}

private fun DrawScope.drawLanes(track: LightFrames, waveform: FloatArray, state: TimelineState) {
    val layout = LaneLayout(size.height, 6.dp.toPx())
    val waveHeight = layout.waveHeight
    val rowHeight = layout.rowHeight
    // Sample every 2 px when zoomed out, every frame when zoomed in.
    val stepPx = max(2f, state.zoom)
    val columnWidth = stepPx + 0.5f

    // Waveform
    val mid = waveHeight / 2
    var x = 0f
    while (x < size.width) {
        val f = state.frameAt(x)
        if (f in waveform.indices) {
            val h = waveform[f] * (waveHeight / 2 - 2f)
            drawRect(Color(0xFF4A4A4A), Offset(x, mid - h), Size(max(1f, stepPx - 1f), h * 2 + 1f))
        }
        x += stepPx
    }

    // Lanes: background bands per strip, then lit cells.
    for ((i, strip) in GlyphZones.stripOrder.withIndex()) {
        val top = layout.tops[i]
        val zones = strip.zones
        drawRect(Color(0xFF141414), Offset(0f, top), Size(size.width, rowHeight * zones.count()))
        for ((row, zone) in zones.withIndex()) {
            val y = top + row * rowHeight
            x = 0f
            while (x < size.width) {
                val f = state.frameAt(x)
                if (f in 0 until track.frameCount) {
                    val v = track[f, zone]
                    if (v > 0) {
                        // True brightness with a faint floor, so dim glow stays readable as dim.
                        drawRect(
                            Color.White.copy(alpha = max(0.06f, v.toFloat() / GlyphZones.MAX_BRIGHTNESS)),
                            Offset(x, y),
                            Size(columnWidth, max(1f, rowHeight - 0.5f)),
                        )
                    }
                }
                x += stepPx
            }
        }
    }
}
