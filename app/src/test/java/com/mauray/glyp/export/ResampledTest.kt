package com.mauray.glyp.export

import com.mauray.glyp.audio.PcmAudio
import org.junit.Assert.assertEquals
import org.junit.Test

class ResampledTest {

    @Test
    fun `same rate and channels pass through untouched`() {
        val pcm = PcmAudio(shortArrayOf(1, -1, 2, -2, 3, -3), 48_000, 2)
        val r = Resampled(pcm, 2, 48_000)
        assertEquals(3, r.frameCount)
        assertEquals(listOf<Short>(1, -1, 2, -2, 3, -3), (0 until 3).flatMap { f -> listOf(r[f, 0], r[f, 1]) })
    }

    @Test
    fun `upsampling interpolates between frames`() {
        val pcm = PcmAudio(shortArrayOf(0, 100, 200), 24_000, 1)
        val r = Resampled(pcm, 1, 48_000)
        assertEquals(6, r.frameCount)
        assertEquals(listOf<Short>(0, 50, 100, 150, 200, 200), (0 until 6).map { r[it, 0] })
    }

    @Test
    fun `extra channels fold evenly into left and right`() {
        // Four channels: L R L' R'
        val pcm = PcmAudio(shortArrayOf(100, 10, 300, 30), 48_000, 4)
        val r = Resampled(pcm, 2, 48_000)
        assertEquals(200.toShort(), r[0, 0])
        assertEquals(20.toShort(), r[0, 1])
    }

    @Test
    fun `spare room after the last frame is never read`() {
        val pcm = PcmAudio(shortArrayOf(7, 7, 9999, 9999), 48_000, 1, frameCount = 2)
        val r = Resampled(pcm, 1, 96_000)
        assertEquals(4, r.frameCount)
        assertEquals(listOf<Short>(7, 7, 7, 7), (0 until 4).map { r[it, 0] })
    }

    @Test
    fun `a window passes just its own frames through`() {
        val pcm = PcmAudio(shortArrayOf(0, 10, 20, 30, 40, 50), 48_000, 1)
        val r = Resampled(pcm, 1, 48_000, from = 2, until = 5)
        assertEquals(3, r.frameCount)
        assertEquals(listOf<Short>(20, 30, 40), (0 until 3).map { r[it, 0] })
    }

    @Test
    fun `resampling a window never reads outside it`() {
        // The frames around the window are loud; none of that may leak into its ends.
        val pcm = PcmAudio(shortArrayOf(9999, 100, 100, 100, 9999), 24_000, 1)
        val r = Resampled(pcm, 1, 48_000, from = 1, until = 4)
        assertEquals(6, r.frameCount)
        assertEquals(List<Short>(6) { 100 }, (0 until 6).map { r[it, 0] })
    }
}
