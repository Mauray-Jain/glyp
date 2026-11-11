package com.mauray.glyp.ui.studio

import com.mauray.glyp.composer.ComposerParams
import com.mauray.glyp.studio.EditTool
import com.mauray.glyp.studio.Pad

/** An edit tool applied to [zones] over [frames]; null or empty means "not picked yet". */
data class PaintRequest(
    val tool: EditTool,
    val frames: IntRange?,
    val zones: Set<Int>,
    val brightness: Float,
    val pulseRate: Float,
)

/**
 * The commands the Studio layout sends to the session, which accepts or rejects each one for
 * the current phase. Plain settings stay in [StudioControls].
 */
interface StudioActions {
    fun togglePlay()
    fun seek(frame: Int)

    /** Regenerates [range] (or the whole song when null) in the given style. */
    fun compose(params: ComposerParams, range: IntRange?)
    fun paint(request: PaintRequest)

    /**
     * Repaints [request] over the selection while a slider is dragged, replacing the previous
     * live paint; does nothing until a range and lights are picked. [endLivePaint] makes it
     * one undo step.
     */
    fun paintLive(request: PaintRequest)
    fun endLivePaint()
    fun undo()
    fun redo()

    /** [level] is the pad brightness, 0..1. */
    fun pressPad(pad: Pad, level: Float)
    fun releasePad(pad: Pad)

    /** Starts a take from the playhead (layering over the lights with [overdub]) or stops it. */
    fun toggleRecord(overdub: Boolean)

    /** Hides the track's own lights so only the pads show (Record with Replace). */
    fun muteTrack(muted: Boolean)
}
