package com.mauray.glyphstudio.ui.studio

import com.mauray.glyphstudio.composer.ComposerParams
import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.studio.EditTool
import com.mauray.glyphstudio.studio.Pad

/** Everything the Studio layout can ask for; implemented by [StudioViewModel]. */
interface StudioActions {
    fun togglePlay()
    fun seek(frame: Int)
    fun setSelection(range: IntRange?)
    fun toggleZone(zone: Int)
    fun selectStrip(strip: GlyphZones.Strip?)
    fun clearZones()
    fun setComposer(params: ComposerParams)
    fun setTool(tool: EditTool)
    fun setBrightness(v: Float)
    fun setPulseRate(v: Float)
    fun autoCompose(selectionOnly: Boolean)
    fun applyTool()
    fun clearLights()
    fun undo()
    fun redo()

    // Record mode
    fun pressPad(pad: Pad)
    fun releasePad(pad: Pad)
    fun toggleRecord()
    fun setOverdub(overdub: Boolean)
    fun setPadBrightness(v: Float)
}
