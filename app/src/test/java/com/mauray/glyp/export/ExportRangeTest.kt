package com.mauray.glyp.export

import com.mauray.glyp.audio.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Test

class ExportRangeTest {

    private val pcm = PcmAudio(ShortArray(48_000 * 10), 48_000, 1)

    @Test
    fun `light frames map to the PCM frames playing at the same time`() {
        // Frame 60 starts at 1 s; frame 120 at 2 s.
        assertEquals(48_000 until 96_000, RingtoneExporter.pcmFrames(pcm, 60..119))
    }

    @Test
    fun `a range past the end of the song stops at its last sample`() {
        assertEquals(432_000 until 480_000, RingtoneExporter.pcmFrames(pcm, 540..9_999))
    }
}
