package com.mauray.glyphstudio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.mauray.glyphstudio.composer.AudioAnalyzer
import com.mauray.glyphstudio.composer.AutoComposer
import com.mauray.glyphstudio.composer.ComposerParams
import com.mauray.glyphstudio.composer.ComposerStyle
import com.mauray.glyphstudio.glyph.GlyphController
import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.model.LightTrack
import com.mauray.glyphstudio.model.ProjectMeta
import com.mauray.glyphstudio.studio.EditTool
import com.mauray.glyphstudio.studio.Pad
import com.mauray.glyphstudio.ui.home.HomeLayout
import com.mauray.glyphstudio.ui.home.StartChoice
import com.mauray.glyphstudio.ui.studio.PaintRequest
import com.mauray.glyphstudio.ui.studio.Phase
import com.mauray.glyphstudio.ui.studio.StudioActions
import com.mauray.glyphstudio.ui.studio.StudioControls
import com.mauray.glyphstudio.ui.studio.StudioLayout
import com.mauray.glyphstudio.ui.studio.StudioMode
import com.mauray.glyphstudio.ui.studio.StudioState
import com.mauray.glyphstudio.ui.theme.GlyphStudioTheme
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import org.junit.Rule
import org.junit.Test

/** Renders the real screens with a composition generated from a synthetic beat. */
class ScreenshotTest {

    @get:Rule
    val paparazzi = Paparazzi(deviceConfig = DeviceConfig.PIXEL_6, showSystemUi = false, useDeviceResolution = true)

    @Test
    fun home() = snap {
        HomeLayout(
            projects = listOf(
                ProjectMeta("1", "Midnight Drive", "a.mp3", 214_000, 0, 1_761_900_000_000),
                ProjectMeta("2", "Lo-fi ringtone", "b.mp3", 12_000, 0, 1_761_800_000_000),
                ProjectMeta("3", "Drum & bass test", "c.ogg", 31_000, 0, 1_761_700_000_000),
            ),
            busy = false, onNew = {}, onOpen = {}, onDelete = {},
        )
    }

    @Test
    fun homeEmpty() = snap { HomeLayout(projects = emptyList(), busy = false, onNew = {}, onOpen = {}, onDelete = {}) }

    @Test
    fun studioAuto() = studio(tab = 0, tall = false)

    @Test
    fun studioAutoFull() = studio(tab = 0, tall = true)

    @Test
    fun studioEditFull() = studio(tab = 1, tall = true, tool = EditTool.SWEEP)

    /** Normal phone height on purpose: everything in Record must fit without scrolling. */
    @Test
    fun studioRecord() = studio(tab = 2, tall = false, recording = true, held = setOf(Pad.TOP))

    @Test
    fun studioRecordIdle() = studio(tab = 2, tall = false)

    @Test
    fun startChoice() = snap {
        // Shown inside a black bottom sheet in the app.
        Box(Modifier.fillMaxSize().background(Color.Black)) { StartChoice {} }
    }

    private fun studio(
        tab: Int,
        tall: Boolean,
        tool: EditTool = EditTool.FILL,
        recording: Boolean = false,
        held: Set<Pad> = emptySet(),
    ) {
        if (tall) paparazzi.unsafeUpdateConfig(DeviceConfig.PIXEL_6.copy(screenHeight = 3500))
        val (track, wave) = demoTrack()
        val state = StudioState.Open(
            phase = if (recording) Phase.Recording else Phase.Idle,
            name = "Midnight Drive",
            track = track,
            waveform = wave,
            playhead = 214,
            lights = IntArray(GlyphZones.COUNT) { z ->
                if (held.any { z in it.zones }) GlyphZones.MAX_BRIGHTNESS else track[214, z]
            },
            heldPads = held,
            revision = 1,
            canUndo = true,
            canRedo = false,
        )
        val controls = StudioControls(StudioMode.entries[tab]).apply {
            this.tool = tool
            selectedZones = GlyphZones.Strip.A.zones.toSet()
            if (!recording) select(150..270)
        }
        snap {
            StudioLayout(
                s = state,
                controls = controls,
                glyphState = GlyphController.State.READY,
                actions = NoActions,
                snackbar = SnackbarHostState(),
                onBack = {},
                onExport = {},
            )
        }
    }

    private fun snap(content: @Composable () -> Unit) = paparazzi.snapshot { GlyphStudioTheme(content) }

    /** 12 s at 44.1 kHz: kick every 0.5 s, hats every 0.25 s, a little melody. */
    private fun demoTrack(): Pair<LightTrack, FloatArray> {
        val sr = 44_100
        val rnd = Random(7)
        val mono = FloatArray(sr * 12) { i ->
            val t = i.toFloat() / sr
            val kt = t % 0.5f
            val ht = t % 0.25f
            val note = 330f * (1 + (t * 2).toInt() % 4 * 0.25f)
            0.8f * sin(2 * PI.toFloat() * 55f * kt) * exp(-kt * 18f) +
                0.15f * (rnd.nextFloat() * 2 - 1) * exp(-ht * 70f) +
                0.12f * sin(2 * PI.toFloat() * note * t) * exp(-(t % 0.5f) * 4f)
        }
        val frames = LightTrack.framesForDuration(12_000)
        val track = LightTrack(frames)
        AutoComposer.compose(AudioAnalyzer.analyze(mono, sr, frames), ComposerParams(ComposerStyle.PULSE), track)
        val perFrame = sr / GlyphZones.FPS
        val wave = FloatArray(frames) { f ->
            var peak = 0f
            for (i in f * perFrame until minOf(mono.size, (f + 1) * perFrame)) peak = max(peak, abs(mono[i]))
            peak
        }
        return track to wave
    }

    private object NoActions : StudioActions {
        override fun togglePlay() {}
        override fun seek(frame: Int) {}
        override fun compose(params: ComposerParams, range: IntRange?) {}
        override fun paint(request: PaintRequest) {}
        override fun undo() {}
        override fun redo() {}
        override fun pressPad(pad: Pad, level: Float) {}
        override fun releasePad(pad: Pad) {}
        override fun toggleRecord(overdub: Boolean) {}
    }
}
