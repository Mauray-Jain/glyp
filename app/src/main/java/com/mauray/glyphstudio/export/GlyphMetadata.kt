package com.mauray.glyphstudio.export

import com.mauray.glyphstudio.glyph.GlyphZones
import com.mauray.glyphstudio.model.LightTrack
import com.mauray.glyphstudio.model.frameToMs
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.Deflater

/**
 * Builds the Vorbis comment tags that Nothing OS reads from a Glyph ringtone (.ogg, Opus).
 *
 * Format as documented by the community tools (SebiAi/custom-nothing-glyph-tools):
 *  - AUTHOR:   light data, one CSV line per 60 fps frame, each value followed by ',', lines
 *              ending in CRLF; zlib (best compression), base64 without '=' padding, 76-char lines.
 *  - CUSTOM1:  timeline dots for Nothing's Glyph Composer UI ("ms-id," pairs), same encoding.
 *  - COMPOSER: "v1-Asteroids Glyph Composer" for the Phone (3a) / (3a) Pro.
 *  - CUSTOM2:  zone count, "36cols".
 */
object GlyphMetadata {

    const val COMPOSER = "v1-Asteroids Glyph Composer"
    const val COLUMNS = "36cols"

    fun tags(title: String, track: LightTrack, durationMs: Long): List<Pair<String, String>> {
        // Light data longer than the audio is ignored by the player, so trim it.
        val frames = minOf(track.frameCount, LightTrack.framesForDuration(durationMs))
        return listOf(
            "TITLE" to title,
            "ALBUM" to "Glyph Studio",
            "AUTHOR" to encode(csv(track, frames)),
            "COMPOSER" to COMPOSER,
            "CUSTOM1" to encode(timelineDots(track, frames)),
            "CUSTOM2" to COLUMNS,
        )
    }

    fun csv(track: LightTrack, frames: Int): ByteArray {
        val sb = StringBuilder(frames * GlyphZones.COUNT * 5)
        for (t in 0 until frames) {
            for (z in 0 until GlyphZones.COUNT) sb.append(track[t, z]).append(',')
            sb.append("\r\n")
        }
        return sb.toString().toByteArray(Charsets.US_ASCII)
    }

    /** One dot per strip each time it switches on, so the Composer app has something to show. */
    fun timelineDots(track: LightTrack, frames: Int): ByteArray {
        val sb = StringBuilder()
        val wasOn = BooleanArray(GlyphZones.Strip.entries.size)
        for (t in 0 until frames) {
            for (strip in GlyphZones.Strip.entries) {
                val on = strip.zones.any { track[t, it] > 0 }
                if (on && !wasOn[strip.ordinal]) sb.append(frameToMs(t)).append('-').append(strip.customId).append(',')
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
}
