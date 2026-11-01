package com.mauray.glyp.ui.studio

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mauray.glyp.composer.ComposerParams
import com.mauray.glyp.glyph.GlyphZones
import com.mauray.glyp.studio.EditTool

enum class StudioMode(val label: String) { COMPOSE("Compose"), EDIT("Edit"), RECORD("Record") }

/**
 * The Studio's plain settings: only the layout reads them, and they reach the session as
 * arguments of the action they belong to. Kept by the ViewModel so they survive rotation.
 */
@Stable
class StudioControls(initialMode: StudioMode = StudioMode.COMPOSE) {
    var mode by mutableStateOf(initialMode)
    var composer by mutableStateOf(ComposerParams())
    var tool by mutableStateOf(EditTool.FILL)
    var brightness by mutableFloatStateOf(1f)
    var pulseRate by mutableFloatStateOf(2f)

    /** Layer pad hits over existing lights (true) or replace them as the song plays (false). */
    var overdub by mutableStateOf(true)
    var padBrightness by mutableFloatStateOf(1f)

    var selection by mutableStateOf<IntRange?>(null)
        private set
    var selectedZones by mutableStateOf(emptySet<Int>())

    /** Sets the time range; a single-frame drag counts as no selection. */
    fun select(range: IntRange?) {
        selection = range?.takeIf { it.last > it.first }
    }

    fun toggleZone(zone: Int) {
        selectedZones = if (zone in selectedZones) selectedZones - zone else selectedZones + zone
    }

    /** Toggles a whole strip (or, for null, every zone) in or out of the selection. */
    fun toggleStrip(strip: GlyphZones.Strip?) {
        val zones = (strip?.zones ?: GlyphZones.ALL).toSet()
        selectedZones = if (selectedZones.containsAll(zones)) selectedZones - zones else selectedZones + zones
    }

    fun paint(tool: EditTool = this.tool) = PaintRequest(tool, selection, selectedZones, brightness, pulseRate)
}
