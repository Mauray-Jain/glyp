package com.mauray.glyp.export

import com.mauray.glyp.export.OggTestFiles.SERIAL
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OggOpusTest {

    private fun retag(tags: List<Pair<String, String>>): Pair<List<TestPage>, List<TestPage>> {
        val (input, audio) = OggTestFiles.sampleOpus()
        return OggTestFiles.parse(OggOpus.retag(input, tags)) to audio
    }

    @Test
    fun `retag replaces the comments and keeps head and audio`() {
        val (pages, audio) = retag(listOf("TITLE" to "Ring", "CUSTOM2" to "36cols"))
        assertTrue("every CRC valid", pages.all { it.crcValid })
        assertEquals(1 + 1 + audio.size, pages.size)
        val packets = OggTestFiles.packets(pages)
        assertTrue(String(packets[0], 0, 8, Charsets.US_ASCII) == "OpusHead")
        assertEquals(listOf("TITLE=Ring", "CUSTOM2=36cols"), OggTestFiles.readComments(packets[1]))
        for ((i, original) in audio.withIndex()) {
            val copy = pages[2 + i]
            assertArrayEquals(original.body, copy.body)
            assertEquals(original.granule, copy.granule)
            assertEquals(original.headerType, copy.headerType)
            assertEquals(original.lacing, copy.lacing)
        }
    }

    @Test
    fun `pages are renumbered in one stream`() {
        val (pages, _) = retag(listOf("A" to "b"))
        assertEquals(pages.indices.toList(), pages.map { it.sequence })
        assertTrue(pages.all { it.serial == SERIAL })
        assertEquals("BOS only on the first page", listOf(0x02), pages.filter { it.headerType and 0x02 != 0 }.map { it.headerType })
    }

    @Test
    fun `tags over 64 KB span pages and audio still starts on a fresh page`() {
        // Random text deflates poorly, so the comment stays big.
        val rnd = Random(1)
        val big = String(CharArray(200_000) { 'a' + rnd.nextInt(26) })
        val (pages, audio) = retag(listOf("AUTHOR" to big))
        assertTrue(pages.all { it.crcValid })
        val tagPages = pages.subList(1, pages.size - audio.size)
        assertTrue("needs several pages, got ${tagPages.size}", tagPages.size >= 4)
        assertTrue("pages hold at most 255 segments", tagPages.all { it.lacing.size <= 255 })
        assertFalse("first tag page starts a packet", tagPages.first().headerType and 0x01 != 0)
        assertTrue("later tag pages continue it", tagPages.drop(1).all { it.headerType and 0x01 != 0 })
        assertTrue("no packet ends before the last tag page", tagPages.dropLast(1).all { it.granule == -1L })
        assertEquals(0L, tagPages.last().granule)
        assertTrue("tag packet ends on its last page", tagPages.last().lacing.last() < 255)
        assertEquals(listOf("AUTHOR=$big"), OggTestFiles.readComments(OggTestFiles.packets(pages)[1]))
        assertArrayEquals(audio.first().body, pages[pages.size - audio.size].body)
    }

    @Test
    fun `a tag packet of an exact multiple of 255 bytes ends with a zero lace`() {
        // 8 + 4 + 4 ("Glyp") + 4 + 4 + len("X=" + value) = 255 * 300
        val value = "v".repeat(255 * 300 - 26)
        val (pages, _) = retag(listOf("X" to value))
        val packets = OggTestFiles.packets(pages)
        assertEquals(255 * 300, packets[1].size)
        assertEquals(listOf("X=$value"), OggTestFiles.readComments(packets[1]))
    }

    @Test
    fun `recognises Ogg Opus`() {
        assertTrue(OggOpus.isOggOpus(OggTestFiles.sampleOpus().first))
        assertFalse(OggOpus.isOggOpus("ID3 not ogg at all, just some mp3 bytes here".toByteArray()))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects other files`() {
        OggOpus.retag(ByteArray(100), emptyList())
    }

    @Test
    fun `readTags reads back what retag wrote, keys upper-cased`() {
        val (input, _) = OggTestFiles.sampleOpus()
        val tags = OggOpus.readTags(OggOpus.retag(input, listOf("title" to "Ring", "CUSTOM2" to "36cols", "X" to "a=b")))
        assertEquals(mapOf("TITLE" to "Ring", "CUSTOM2" to "36cols", "X" to "a=b"), tags)
    }

    @Test
    fun `readTags reads a tag packet over 64 KB spanning pages`() {
        val rnd = Random(1)
        val big = String(CharArray(200_000) { 'a' + rnd.nextInt(26) })
        val (input, _) = OggTestFiles.sampleOpus()
        assertEquals(big, OggOpus.readTags(OggOpus.retag(input, listOf("AUTHOR" to big)))["AUTHOR"])
    }

    @Test
    fun `readTags of something that isn't Ogg Opus is empty`() {
        assertEquals(emptyMap<String, String>(), OggOpus.readTags(Random(3).nextBytes(4_000)))
        assertEquals(emptyMap<String, String>(), OggOpus.readTags(ByteArray(0)))
        // Starts like Ogg Opus but is cut off inside the first page.
        val (input, _) = OggTestFiles.sampleOpus()
        assertEquals(emptyMap<String, String>(), OggOpus.readTags(input.copyOf(60)))
    }
}
