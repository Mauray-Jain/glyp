package com.mauray.glyp.export

import com.mauray.glyp.audio.Timebase
import com.mauray.glyp.glyph.GlyphZones
import com.mauray.glyp.model.LightFrames
import com.mauray.glyp.model.LightTrack
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Turns a light snapshot and Ogg Opus audio into a Glyph ringtone: the same audio with the
 * light data in the Vorbis comment tags that Nothing OS reads. Pure JVM, no Android.
 *
 * Tag format as documented by the community tools (SebiAi/custom-nothing-glyph-tools):
 *  - AUTHOR:   light data, one CSV line per 60 fps frame, each value followed by ',', lines
 *              ending in CRLF; zlib (best compression), base64 without '=' padding, 76-char lines.
 *  - CUSTOM1:  timeline dots for Nothing's Glyph Composer UI ("ms-id," pairs), same encoding.
 *  - COMPOSER: "v1-Asteroids Glyph Composer" for the Phone (3a) / (3a) Pro.
 *  - CUSTOM2:  zone count, "36cols".
 */
object GlyphRingtone {

    const val COMPOSER = "v1-Asteroids Glyph Composer"
    const val COLUMNS = "36cols"

    /** The tagged ringtone file for [opus] (an Ogg Opus file) and [lights] (a snapshot). */
    fun build(title: String, lights: LightFrames, durationMs: Long, opus: ByteArray): ByteArray =
        OggOpus.retag(opus, tags(title, lights, durationMs))

    fun tags(title: String, lights: LightFrames, durationMs: Long): List<Pair<String, String>> {
        // Light data longer than the audio is ignored by the player, so trim it.
        val frames = minOf(lights.frameCount, Timebase.framesFor(durationMs))
        return listOf(
            "TITLE" to title,
            "ALBUM" to "Glyp",
            "AUTHOR" to encode(csv(lights, frames)),
            "COMPOSER" to COMPOSER,
            "CUSTOM1" to encode(timelineDots(lights, frames)),
            "CUSTOM2" to COLUMNS,
        )
    }

    fun csv(lights: LightFrames, frames: Int): ByteArray {
        val sb = StringBuilder(frames * GlyphZones.COUNT * 5)
        for (t in 0 until frames) {
            for (z in 0 until GlyphZones.COUNT) sb.append(lights[t, z]).append(',')
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** One dot per strip each time it switches on, so the Composer app has something to show. */
    fun timelineDots(lights: LightFrames, frames: Int): ByteArray {
        val sb = StringBuilder()
        val wasOn = BooleanArray(GlyphZones.Strip.entries.size)
        for (t in 0 until frames) {
            for (strip in GlyphZones.Strip.entries) {
                val on = strip.zones.any { lights[t, it] > 0 }
                if (on && !wasOn[strip.ordinal]) sb.append(Timebase.msAt(t)).append('-').append(strip.customId).append(',')
                wasOn[strip.ordinal] = on
            }
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    fun encode(data: ByteArray): String {
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream(data.size / 4 + 64)
        val buf = ByteArray(16 * 1024)
        while (!deflater.finished()) out.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(out.toByteArray())
        return b64.chunked(76).joinToString("\n", postfix = "\n")
    }

    /**
     * The light data of a Glyph ringtone's [tags] (the inverse of [csv] + [encode]), or null
     * when there is none, it doesn't decode, or it isn't for the Phone (3a)'s 36 zones (other
     * Nothing phones use other column counts).
     */
    fun decodeLights(tags: Map<String, String>): LightTrack? {
        val author = tags["AUTHOR"] ?: return null
        val csv = runCatching { inflate(Base64.getDecoder().decode(author.filterNot(Char::isWhitespace))) }
            .getOrNull() ?: return null
        val rows = String(csv, Charsets.US_ASCII).split('\n')
            .map { it.trimEnd('\r') }
            .filter { it.isNotEmpty() }
            .map { line -> line.split(',').filter { it.isNotEmpty() } }
        if (rows.isEmpty() || rows.any { it.size != GlyphZones.COUNT }) return null
        val track = LightTrack(rows.size)
        for ((frame, values) in rows.withIndex()) {
            for ((zone, value) in values.withIndex()) {
                track[frame, zone] = value.toIntOrNull()?.coerceIn(0, GlyphZones.MAX_BRIGHTNESS) ?: return null
            }
        }
        return track
    }

    private fun inflate(data: ByteArray): ByteArray {
        val inflater = Inflater()
        inflater.setInput(data)
        val out = ByteArrayOutputStream(data.size * 4)
        val buf = ByteArray(16 * 1024)
        try {
            while (!inflater.finished()) {
                val n = inflater.inflate(buf)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) error("Truncated light data")
                out.write(buf, 0, n)
            }
        } finally {
            inflater.end()
        }
        return out.toByteArray()
    }
}
