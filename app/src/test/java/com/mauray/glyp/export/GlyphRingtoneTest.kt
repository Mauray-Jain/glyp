package com.mauray.glyp.export

import com.mauray.glyp.glyph.GlyphZones
import com.mauray.glyp.model.LightTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64
import java.util.zip.Inflater

class GlyphRingtoneTest {

    private fun decode(tag: String): String {
        assertFalse("no padding", tag.contains('='))
        assertTrue("76-char lines", tag.lines().all { it.length <= 76 })
        val zipped = Base64.getMimeDecoder().decode(tag)
        val inflater = Inflater().apply { setInput(zipped) }
        val out = ByteArray(1 shl 20)
        val n = inflater.inflate(out)
        return String(out, 0, n, Charsets.US_ASCII)
    }

    /** Frame 0: zone 0 full, zone 35 at 7. Frame 1: right strip at 100. Frame 2: dark. */
    private fun track() = LightTrack(3).apply {
        this[0, 0] = 4095
        this[0, 35] = 7
        for (z in GlyphZones.Strip.A.zones) this[1, z] = 100
    }

    @Test
    fun `csv matches the golden format`() {
        val zeros = { n: Int -> "0,".repeat(n) }
        val golden =
            "4095," + zeros(34) + "7,\r\n" +
                zeros(20) + "100,".repeat(11) + zeros(5) + "\r\n" +
                zeros(36) + "\r\n"
        assertEquals(golden, String(GlyphRingtone.csv(track(), 3), Charsets.US_ASCII))
    }

    @Test
    fun `timeline dots mark each strip switching on`() {
        // C on at 0 ms, B on at 0 ms, A on at frame 1 (which starts at 16.7 ms, so 17).
        assertEquals("0-0,0-2,17-1,", String(GlyphRingtone.timelineDots(track(), 3), Charsets.US_ASCII))
    }

    @Test
    fun `tags carry the encoded lights trimmed to the song`() {
        val tags = GlyphRingtone.tags("Ring", track(), durationMs = 20).toMap()
        assertEquals("Ring", tags["TITLE"])
        assertEquals(GlyphRingtone.COMPOSER, tags["COMPOSER"])
        assertEquals(GlyphRingtone.COLUMNS, tags["CUSTOM2"])
        // 20 ms of song is two frames: the third is dropped.
        assertEquals(String(GlyphRingtone.csv(track(), 2), Charsets.US_ASCII), decode(tags.getValue("AUTHOR")))
        assertEquals("0-0,0-2,17-1,", decode(tags.getValue("CUSTOM1")))
    }

    @Test
    fun `build writes the tags into the audio`() {
        val (input, audio) = OggTestFiles.sampleOpus()
        val pages = OggTestFiles.parse(GlyphRingtone.build("Ring", track(), 50, input))
        val comments = OggTestFiles.readComments(OggTestFiles.packets(pages)[1])
        assertEquals(listOf("TITLE", "ALBUM", "AUTHOR", "COMPOSER", "CUSTOM1", "CUSTOM2"), comments.map { it.substringBefore('=') })
        assertEquals(audio.size, pages.size - 2)
    }

    @Test
    fun `decoding the tags gives back the lights frame by frame`() {
        val lights = LightTrack(90).apply {
            for (f in 0 until 90) for (z in 0 until GlyphZones.COUNT) this[f, z] = (f * 37 + z * 113) % 4096
        }
        val decoded = GlyphRingtone.decodeLights(GlyphRingtone.tags("Ring", lights, durationMs = 1_500).toMap())!!
        assertEquals(90, decoded.frameCount)
        for (f in 0 until 90) for (z in 0 until GlyphZones.COUNT) assertEquals("frame $f zone $z", lights[f, z], decoded[f, z])
    }

    @Test
    fun `lights for another phone's column count are refused`() {
        val fifteen = ("4095," + "0,".repeat(14) + "\r\n").repeat(10).toByteArray()
        assertNull(GlyphRingtone.decodeLights(mapOf("AUTHOR" to GlyphRingtone.encode(fifteen))))
    }

    @Test
    fun `undecodable light data is refused`() {
        assertNull(GlyphRingtone.decodeLights(mapOf("AUTHOR" to "!!! not base64 !!!")))
        assertNull(GlyphRingtone.decodeLights(mapOf("AUTHOR" to "aGVsbG8gd29ybGQ")))
    }

    @Test
    fun `a ringtone without light data has none`() {
        assertNull(GlyphRingtone.decodeLights(mapOf("TITLE" to "Plain song")))
    }
}
