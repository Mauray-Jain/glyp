package com.mauray.glyp.glyph

/**
 * What [GlyphController] needs from Android and Nothing's Glyph SDK, so its connection logic
 * can be tested on the JVM with a fake. Calls arrive on the main thread.
 */
interface GlyphPlatform {
    /** True on a Phone (3a) / (3a) Pro (the SDK's `Common.is24111()`). */
    val supported: Boolean

    /** Binds to the Glyph service; the callbacks arrive later, possibly more than once. */
    fun bind(onConnected: () -> Unit, onDisconnected: () -> Unit)
    fun unbind()

    /** `GlyphManager.register(Glyph.DEVICE_24111)`. */
    fun register(): Boolean

    /** False when the SDK threw. */
    fun openSession(): Boolean

    /** Errors are logged and swallowed, as today. */
    fun closeSession()

    /** Errors are logged and swallowed. */
    fun turnOff()

    /** Errors are logged and swallowed: a dropped frame is replaced by the next one. */
    fun setFrameColors(colors: IntArray)

    /** `Settings.Global` integer, or null when missing or unreadable. */
    fun readGlobal(name: String): Int?
    fun watchGlobals(names: List<String>, onChange: () -> Unit)
    fun unwatchGlobals()

    /** Replaces any task posted earlier: there is only ever one pending reconnect. */
    fun postDelayed(delayMs: Long, task: () -> Unit)
    fun cancelPosted()

    fun log(message: String)
}
