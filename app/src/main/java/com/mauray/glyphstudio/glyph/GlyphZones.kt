package com.mauray.glyphstudio.glyph

/**
 * Zone layout of the Phone (3a) / (3a) Pro (SDK device 24111, ringtone codename "Asteroids").
 *
 * Indices are shared by the Glyph SDK and the ringtone CSV format:
 *  - C:  0..19  top-left strip, from bottom-left to top-right
 *  - A: 20..30  middle-right strip, from top to bottom
 *  - B: 31..35  bottom-left strip, from bottom-right to top-left
 */
object GlyphZones {
    const val COUNT = 36

    /** Brightness range used by ringtone files (12 bit). */
    const val MAX_BRIGHTNESS = 4095

    /** Frames per second of ringtone light data (one CSV line every 16.666 ms). */
    const val FPS = 60

    /** [label] is the SDK/ringtone letter; [displayName] is what users see. */
    enum class Strip(val label: String, val displayName: String, val zones: IntRange, val customId: Int) {
        C("C", "Top", 0..19, 0),
        A("A", "Right", 20..30, 1),
        B("B", "Bottom", 31..35, 2),
    }

    /** Display order for UI lists and the timeline (top of the phone first). */
    val stripOrder = listOf(Strip.C, Strip.A, Strip.B)

    fun stripOf(zone: Int): Strip = Strip.entries.first { zone in it.zones }

    val ALL: IntRange = 0 until COUNT
}
