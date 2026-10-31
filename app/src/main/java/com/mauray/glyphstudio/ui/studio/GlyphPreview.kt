package com.mauray.glyphstudio.ui.studio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.glyph.GlyphZones.Strip
import com.mauray.glyphstudio.ui.theme.NothingRed
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The back of the Phone (3a) as Nothing's own Glyph Composer draws it: the round camera
 * island, with the three strips as arcs around it. Angles are screen degrees (clockwise from
 * 3 o'clock) measured off the stock Composer.
 *  - C: upper left, C1 at the lower end → C20 at the top
 *  - A: right side, A1 at the top → A11 at the bottom
 *  - B: short arc lower left, B1 at the lower end → B5 at the upper end
 */
private class StripArc(val strip: Strip, val firstDeg: Float, val lastDeg: Float)

private val arcs = listOf(
    StripArc(Strip.C, 198f, 239f),
    StripArc(Strip.A, 346f, 391f),
    StripArc(Strip.B, 135f, 154f),
)

private val Off = Color(0xFF4A4A4A)

/** One size everywhere, so the phone doesn't grow or shrink when switching modes. */
internal val PreviewHeight = 260.dp
private val RingLine = Color(0xFF2A2A2A)

@Composable
fun GlyphPreview(
    levels: (Int) -> Int,
    selected: Set<Int>,
    onZoneTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // The gesture loop outlives recompositions; read the latest callback, not the first one.
    val currentOnZoneTap by rememberUpdatedState(onZoneTap)
    Canvas(
        modifier.pointerInput(Unit) {
            detectTapGestures { pos ->
                val g = Geometry(Size(size.width.toFloat(), size.height.toFloat()))
                val hit = (0 until GlyphZones.COUNT).minByOrNull { z -> g.zoneCenter(z).let { hypot(pos.x - it.x, pos.y - it.y) } }
                    ?: return@detectTapGestures
                val c = g.zoneCenter(hit)
                if (hypot(pos.x - c.x, pos.y - c.y) < g.ring * 0.3f) currentOnZoneTap(hit)
            }
        },
    ) {
        val g = Geometry(size)
        drawCameraIsland(g)

        for (arc in arcs) {
            val zones = arc.strip.zones
            val step = (arc.lastDeg - arc.firstDeg) / zones.count()
            // Soft glow behind lit segments.
            for ((i, zone) in zones.withIndex()) {
                val level = levels(zone) / GlyphZones.MAX_BRIGHTNESS.toFloat()
                if (level > 0.02f) {
                    drawSegment(g, arc.firstDeg + i * step, step, Color.White.copy(alpha = 0.10f * level), g.stroke * 2.6f)
                }
            }
            // Segments, opaque and slightly overlapping so the strip reads as one continuous bar.
            for ((i, zone) in zones.withIndex()) {
                val level = levels(zone) / GlyphZones.MAX_BRIGHTNESS.toFloat()
                drawSegment(g, arc.firstDeg + i * step - 0.2f, step + 0.4f, lerp(Off, Color.White, level), g.stroke)
            }
            // Round end caps take the colour of the end segments.
            for ((zone, deg) in listOf(zones.first to arc.firstDeg, zones.last to arc.lastDeg)) {
                val level = levels(zone) / GlyphZones.MAX_BRIGHTNESS.toFloat()
                drawCircle(lerp(Off, Color.White, level), g.stroke / 2, g.point(deg, g.ring))
            }
            // Selection: a thin red arc just outside the strip.
            for ((i, zone) in zones.withIndex()) {
                if (zone in selected) {
                    drawArc(
                        NothingRed, arc.firstDeg + i * step, step, false,
                        topLeft = g.center - Offset(g.selectRadius, g.selectRadius),
                        size = Size(g.selectRadius * 2, g.selectRadius * 2),
                        style = Stroke(2.dp.toPx(), cap = StrokeCap.Butt),
                    )
                }
            }
        }
    }
}

private class Geometry(size: Size) {
    val center = Offset(size.width / 2, size.height / 2)
    /** Radius of the thin camera ring; everything else scales from it. */
    val inner = min(size.width, size.height) / 2 / 1.32f
    val ring = inner * 1.13f
    val stroke = inner * 0.085f
    val selectRadius = ring + stroke * 1.15f

    fun point(deg: Float, radius: Float): Offset {
        val r = deg * PI.toFloat() / 180f
        return Offset(center.x + radius * cos(r), center.y + radius * sin(r))
    }

    fun zoneCenter(zone: Int): Offset {
        val arc = arcs.first { zone in it.strip.zones }
        val i = zone - arc.strip.zones.first
        val step = (arc.lastDeg - arc.firstDeg) / arc.strip.zones.count()
        return point(arc.firstDeg + (i + 0.5f) * step, ring)
    }
}

private fun DrawScope.drawSegment(g: Geometry, startDeg: Float, sweepDeg: Float, color: Color, width: Float) {
    drawArc(
        color, startDeg, sweepDeg, false,
        topLeft = g.center - Offset(g.ring, g.ring),
        size = Size(g.ring * 2, g.ring * 2),
        style = Stroke(width, cap = StrokeCap.Butt),
    )
}

private fun DrawScope.drawCameraIsland(g: Geometry) {
    val line = Stroke(1.5.dp.toPx())
    val r = g.inner
    val c = g.center
    drawCircle(RingLine, r, c, style = line)
    // Outer pill holding both lenses.
    val outer = Rect(c.x - r * 0.81f, c.y - r * 0.35f, c.x + r * 0.81f, c.y + r * 0.35f)
    drawRoundRect(RingLine, outer.topLeft, outer.size, CornerRadius(outer.height / 2), style = line)
    // Main lens (wide pill) and second lens (circle).
    val main = Rect(c.x - r * 0.68f, c.y - r * 0.215f, c.x + r * 0.21f, c.y + r * 0.215f)
    drawRoundRect(RingLine, main.topLeft, main.size, CornerRadius(main.height / 2), style = line)
    drawCircle(RingLine, r * 0.185f, Offset(c.x + r * 0.46f, c.y), style = line)
    // Flash.
    drawCircle(RingLine, r * 0.055f, Offset(c.x - r * 0.23f, c.y - r * 0.69f), style = line)
}
