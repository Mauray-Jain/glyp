package com.mauray.glyphstudio.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Replaces the OpusTags header of an Ogg Opus file with our own comments.
 *
 * MediaMuxer cannot write custom tags, so after muxing we re-page the stream: the OpusHead
 * page is kept, a new OpusTags packet is laid out over as many pages as it needs, and every
 * audio page is copied with a renumbered sequence and fresh CRC.
 */
object OggOpus {

    /** [input] is an Ogg Opus file; returns the same stream with [tags] as its comments. */
    fun retag(input: ByteArray, tags: List<Pair<String, String>>): ByteArray {
        val pages = parsePages(input)
        require(pages.isNotEmpty() && pages[0].packetStartsWith("OpusHead")) { "Not an Ogg Opus file" }

        // The OpusTags packet starts on page 1 and must end its page; audio starts on a fresh page.
        var tagsEnd = 1
        while (tagsEnd < pages.size && pages[tagsEnd].lastPacketContinues) tagsEnd++
        require(tagsEnd < pages.size) { "Truncated OpusTags header" }
        val firstAudioPage = tagsEnd + 1

        val serial = pages[0].serial
        val out = ByteArrayOutputStream()
        var sequence = 0
        writePage(out, pages[0].copy(sequence = sequence++))
        for (page in tagPages(buildOpusTags(tags), serial, startSequence = sequence)) {
            writePage(out, page)
            sequence++
        }
        for (i in firstAudioPage until pages.size) {
            writePage(out, pages[i].copy(sequence = sequence++))
        }
        return out.toByteArray()
    }

    /** True when [bytes] start with an Ogg page carrying an OpusHead packet. */
    fun isOggOpus(bytes: ByteArray): Boolean =
        bytes.size > 36 && String(bytes, 0, 4, Charsets.US_ASCII) == "OggS" &&
            String(bytes, 28, 8, Charsets.US_ASCII) == "OpusHead"

    private fun buildOpusTags(tags: List<Pair<String, String>>): ByteArray {
        val vendor = "Glyp".toByteArray()
        val comments = tags.map { (k, v) -> "$k=$v".toByteArray(Charsets.UTF_8) }
        val size = 8 + 4 + vendor.size + 4 + comments.sumOf { 4 + it.size }
        val buf = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("OpusTags".toByteArray())
        buf.putInt(vendor.size).put(vendor)
        buf.putInt(comments.size)
        comments.forEach { buf.putInt(it.size).put(it) }
        return buf.array()
    }

    /** Lays one packet over pages of up to 255 lacing segments. */
    private fun tagPages(packet: ByteArray, serial: Int, startSequence: Int): List<Page> {
        val pages = mutableListOf<Page>()
        var offset = 0
        var sequence = startSequence
        var continued = false
        while (true) {
            val lacing = mutableListOf<Int>()
            val start = offset
            var finished = false
            while (lacing.size < 255) {
                val seg = minOf(255, packet.size - offset)
                lacing += seg
                offset += seg
                if (seg < 255) { finished = true; break }
            }
            pages += Page(
                headerType = if (continued) 0x01 else 0x00,
                // Pages on which no packet ends carry granule -1.
                granule = if (finished) 0L else -1L,
                serial = serial,
                sequence = sequence++,
                lacing = lacing.toIntArray(),
                body = packet.copyOfRange(start, offset),
            )
            if (finished) return pages
            continued = true
        }
    }

    private data class Page(
        val headerType: Int,
        val granule: Long,
        val serial: Int,
        val sequence: Int,
        val lacing: IntArray,
        val body: ByteArray,
    ) {
        /** True when the last lacing value is 255, i.e. the final packet goes on in the next page. */
        val lastPacketContinues get() = lacing.isNotEmpty() && lacing.last() == 255

        fun packetStartsWith(magic: String) =
            body.size >= magic.length && String(body, 0, magic.length, Charsets.US_ASCII) == magic
    }

    private fun parsePages(bytes: ByteArray): List<Page> {
        val pages = mutableListOf<Page>()
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 0
        while (pos + 27 <= bytes.size) {
            require(bytes[pos] == 'O'.code.toByte() && bytes[pos + 1] == 'g'.code.toByte() &&
                bytes[pos + 2] == 'g'.code.toByte() && bytes[pos + 3] == 'S'.code.toByte()) { "Bad Ogg page at $pos" }
            val headerType = bytes[pos + 5].toInt() and 0xFF
            val granule = buf.getLong(pos + 6)
            val serial = buf.getInt(pos + 14)
            val sequence = buf.getInt(pos + 18)
            val segments = bytes[pos + 26].toInt() and 0xFF
            val lacing = IntArray(segments) { bytes[pos + 27 + it].toInt() and 0xFF }
            val bodyStart = pos + 27 + segments
            val bodyEnd = bodyStart + lacing.sum()
            require(bodyEnd <= bytes.size) { "Truncated Ogg page at $pos" }
            pages += Page(headerType, granule, serial, sequence, lacing, bytes.copyOfRange(bodyStart, bodyEnd))
            pos = bodyEnd
        }
        return pages
    }

    private fun writePage(out: ByteArrayOutputStream, page: Page) {
        val header = ByteBuffer.allocate(27 + page.lacing.size).order(ByteOrder.LITTLE_ENDIAN)
        header.put("OggS".toByteArray())
        header.put(0)
        header.put(page.headerType.toByte())
        header.putLong(page.granule)
        header.putInt(page.serial)
        header.putInt(page.sequence)
        header.putInt(0) // CRC placeholder
        header.put(page.lacing.size.toByte())
        page.lacing.forEach { header.put(it.toByte()) }
        val bytes = header.array() + page.body
        val crc = OggCrc.compute(bytes)
        ByteBuffer.wrap(bytes, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc)
        out.write(bytes)
    }
}

/** Ogg's CRC-32: polynomial 0x04C11DB7, no reflection, zero init, no final xor. */
internal object OggCrc {
    private val table = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    fun compute(data: ByteArray): Int {
        var crc = 0
        for (b in data) crc = (crc shl 8) xor table[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        return crc
    }
}
