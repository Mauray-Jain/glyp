package com.mauray.glyp.model

import org.junit.Assert.assertEquals
import org.junit.Test

class LightTrackTest {

    private fun track() = LightTrack(10).apply { for (f in 0 until 10) this[f, 5] = f * 100 }

    @Test
    fun `a slice copies just those frames`() {
        val slice = track().slice(3..6)
        assertEquals(4, slice.frameCount)
        assertEquals(listOf(300, 400, 500, 600), (0 until 4).map { slice[it, 5] })
        assertEquals(0, slice[0, 4])
    }

    @Test
    fun `a slice is independent of the track`() {
        val t = track()
        val slice = t.slice(0..1)
        slice[0, 5] = 4095
        assertEquals(0, t[0, 5])
    }

    @Test
    fun `a slice past either end is clamped to the track`() {
        val slice = track().slice(-5..3)
        assertEquals(4, slice.frameCount)
        assertEquals(0, slice[0, 5])
        assertEquals(2, track().slice(8..50).frameCount)
        assertEquals(0, track().slice(20..30).frameCount)
    }
}
