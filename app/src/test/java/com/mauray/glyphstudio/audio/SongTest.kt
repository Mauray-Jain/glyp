package com.mauray.glyphstudio.audio

import com.mauray.glyphstudio.glyph.GlyphZones
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SongTest {

    @Test
    fun `frame to time and back is exact`() {
        for (f in 0..200_000) assertEquals(f, Timebase.frameAt(Timebase.msAt(f)))
    }

    @Test
    fun `every millisecond belongs to the frame that starts at or before it`() {
        for (ms in 0L..10_000L) {
            val f = Timebase.frameAt(ms)
            assertTrue(Timebase.msAt(f) <= ms && ms < Timebase.msAt(f + 1))
        }
    }

    @Test
    fun `frames cover the whole song`() {
        assertEquals(0, Timebase.framesFor(0))
        assertEquals(1, Timebase.framesFor(1))
        assertEquals(60, Timebase.framesFor(1_000))
        assertEquals(61, Timebase.framesFor(1_001))
        assertTrue(Timebase.msAt(Timebase.framesFor(12_345)) >= 12_345)
    }

    @Test
    fun `waveform is per frame and normalised to the loudest`() {
        val rate = 6_000 // 100 samples per frame
        val samples = ShortArray(rate) { i ->
            when (i / 100) {
                10 -> 16_000
                20 -> -8_000
                else -> 0
            }.toShort()
        }
        val song = Song.of(PcmAudio(samples, rate, 1), frameCount = 60)
        assertEquals(60, song.waveform.size)
        assertEquals(1f, song.waveform[10], 1e-4f)
        assertEquals(0.5f, song.waveform[20], 1e-4f)
        assertEquals(0f, song.waveform[30])
        assertEquals(60, song.analysis.frameCount)
    }

    @Test
    fun `silence stays flat and stereo is folded to mono`() {
        val silent = Song.of(PcmAudio(ShortArray(2 * 6_000), 6_000, 2), frameCount = 60)
        assertTrue(silent.waveform.all { it == 0f })
        val stereo = PcmAudio(shortArrayOf(1000, 3000, -1000, -3000), 48_000, 2)
        val mono = stereo.toMono()
        assertEquals(2, mono.size)
        assertEquals(2000f / 32768f, mono[0], 1e-6f)
        assertEquals(GlyphZones.FPS, Timebase.framesFor(1_000))
    }
}
