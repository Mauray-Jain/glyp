package com.mauray.glyp.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** An Ogg page as the tests see it, parsed and written independently of the production code. */
data class TestPage(
    val headerType: Int,
    val granule: Long,
    val serial: Int,
    val sequence: Int,
    val lacing: List<Int>,
    val body: ByteArray,
    val crcValid: Boolean = true,
)

object OggTestFiles {

    /** Bit-by-bit Ogg CRC (poly 0x04C11DB7, no reflection), not the production table version. */
    fun crc(bytes: ByteArray): Int {
        var crc = 0
        for (b in bytes) {
            crc = crc xor ((b.toInt() and 0xFF) shl 24)
            repeat(8) { crc = if (crc < 0) (crc shl 1) xor 0x04C11DB7 else crc shl 1 }
        }
        return crc
    }

    fun write(pages: List<TestPage>): ByteArray {
        val out = ByteArrayOutputStream()
        for (p in pages) {
            val header = ByteBuffer.allocate(27 + p.lacing.size).order(ByteOrder.LITTLE_ENDIAN)
            header.put("OggS".toByteArray()).put(0).put(p.headerType.toByte()).putLong(p.granule)
                .putInt(p.serial).putInt(p.sequence).putInt(0).put(p.lacing.size.toByte())
            p.lacing.forEach { header.put(it.toByte()) }
            val bytes = header.array() + p.body
            ByteBuffer.wrap(bytes, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc(bytes))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): List<TestPage> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val pages = mutableListOf<TestPage>()
        var pos = 0
        while (pos < bytes.size) {
            check(String(bytes, pos, 4, Charsets.US_ASCII) == "OggS") { "no capture pattern at $pos" }
            check(bytes[pos + 4].toInt() == 0) { "bad version" }
            val segments = bytes[pos + 26].toInt() and 0xFF
            val lacing = (0 until segments).map { bytes[pos + 27 + it].toInt() and 0xFF }
            val end = pos + 27 + segments + lacing.sum()
            val page = bytes.copyOfRange(pos, end)
            val stored = buf.getInt(pos + 22)
            ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(0)
            pages += TestPage(
                headerType = bytes[pos + 5].toInt() and 0xFF,
                granule = buf.getLong(pos + 6),
                serial = buf.getInt(pos + 14),
                sequence = buf.getInt(pos + 18),
                lacing = lacing,
                body = bytes.copyOfRange(pos + 27 + segments, end),
                crcValid = crc(page) == stored,
            )
            pos = end
        }
        return pages
    }

    /** Splits the stream back into packets, following lacing across page boundaries. */
    fun packets(pages: List<TestPage>): List<ByteArray> {
        val packets = mutableListOf<ByteArray>()
        val current = ByteArrayOutputStream()
        for (page in pages) {
            var offset = 0
            for (l in page.lacing) {
                current.write(page.body, offset, l)
                offset += l
                if (l < 255) {
                    packets += current.toByteArray()
                    current.reset()
                }
            }
        }
        return packets
    }

    fun opusTags(comments: List<String>, vendor: String = "test"): ByteArray {
        val out = ByteArrayOutputStream()
        fun int(v: Int) = out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array())
        out.write("OpusTags".toByteArray())
        int(vendor.length); out.write(vendor.toByteArray())
        int(comments.size)
        comments.forEach { val b = it.toByteArray(); int(b.size); out.write(b) }
        return out.toByteArray()
    }

    fun readComments(packet: ByteArray): List<String> {
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        check(String(packet, 0, 8, Charsets.US_ASCII) == "OpusTags")
        buf.position(8)
        val vendor = buf.int
        buf.position(buf.position() + vendor)
        return List(buf.int) {
            val len = buf.int
            val s = String(packet, buf.position(), len, Charsets.UTF_8)
            buf.position(buf.position() + len)
            s
        }
    }

    const val SERIAL = 0x1234

    /** OpusHead page, a one-page OpusTags, then three audio pages; the last carries EOS. */
    fun sampleOpus(): Pair<ByteArray, List<TestPage>> {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        val audio = (0 until 3).map { i ->
            val packets = listOf(ByteArray(120) { (i * 7 + it).toByte() }, ByteArray(300) { (it * 3).toByte() })
            TestPage(
                headerType = if (i == 2) 0x04 else 0,
                granule = 960L * (i + 1) * 2,
                serial = SERIAL,
                sequence = i + 2,
                lacing = packets.flatMap { lace(it.size) },
                body = packets.reduce { a, b -> a + b },
            )
        }
        val pages = listOf(
            TestPage(0x02, 0, SERIAL, 0, lace(head.size), head),
            TestPage(0, 0, SERIAL, 1, lace(opusTags(listOf("ENCODER=x")).size), opusTags(listOf("ENCODER=x"))),
        ) + audio
        return write(pages) to audio
    }

    private fun lace(size: Int): List<Int> = List(size / 255) { 255 } + (size % 255)
}
