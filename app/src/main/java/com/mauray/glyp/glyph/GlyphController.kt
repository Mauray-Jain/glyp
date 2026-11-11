package com.mauray.glyp.glyph

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Drives all 36 zones of the Phone (3a) with per-zone brightness through Nothing's Glyph SDK
 * ([GlyphPlatform]). On any other device every call is a no-op, so the rest of the app
 * (editor, export) still works and only the live preview on the back is missing.
 */
class GlyphController(private val platform: GlyphPlatform) {

    // Spelled out so this file stays free of Android imports; only the adapter needs them.
    constructor(context: android.content.Context) : this(AndroidGlyphPlatform(context))

    /**
     * [SWITCHED_OFF]: connected, but Nothing OS has the Glyph Interface off (its toggle or
     * schedule). [SCREEN_UP]: connected, but the phone lies screen-up, and Nothing OS keeps
     * the Glyphs dark then since they face the table.
     */
    enum class State { UNSUPPORTED, CONNECTING, READY, SWITCHED_OFF, SCREEN_UP, FAILED }

    /** The connection's own state; [state] also says when the system switch is off. */
    private var connection = if (platform.supported) State.CONNECTING else State.UNSUPPORTED
        set(value) {
            field = value
            publish()
        }

    /** Nothing OS's Glyph switch, and whether the phone lies screen-up; null when unreadable. */
    private var systemOn: Boolean? = null
    private var screenUp: Boolean? = null
    private val _state = MutableStateFlow(connection)
    val state: StateFlow<State> = _state.asStateFlow()

    private fun publish() {
        _state.value = when {
            connection != State.READY -> connection
            systemOn == false -> State.SWITCHED_OFF
            screenUp == true -> State.SCREEN_UP
            else -> connection
        }
    }

    // Bound once, the first time the app comes to the foreground, and kept for the life of
    // the process: binding and unbinding on every trip to the background gave a lost bind
    // many chances to leave the app stuck. Only the session follows the app's visibility.
    private var bound = false
    private var registered = false
    private var visible = false
    private val buffer = IntArray(GlyphZones.COUNT)

    // The SDK never rebinds on its own: if the binding dies, or a bind never connects, the
    // app would stay CONNECTING with the lights dark. So while visible and not registered,
    // bind again, backing off.
    private var retries = 0

    private fun reconnect() {
        if (!bound) return
        if (registered || !visible) return
        platform.log("Glyph service not connected; binding again")
        platform.unbind()
        bind()
        scheduleReconnect()
    }

    private fun bind() = platform.bind(::onServiceConnected, ::onServiceDisconnected)

    // Watched while visible, so the status follows the Glyphs tile, the schedule and the
    // phone being laid screen-up as they change.
    private fun readSystemSwitch() {
        systemOn = platform.readGlobal(SYSTEM_SWITCH)?.let { it != 0 }
        screenUp = platform.readGlobal(SCREEN_UPWARD)?.let { it == SCREEN_UPWARD_UP }
        publish()
    }

    private fun scheduleReconnect() {
        platform.cancelPosted()
        platform.postDelayed(minOf(RETRY_FIRST_MS shl minOf(retries++, 4), RETRY_MAX_MS), ::reconnect)
    }

    private fun onServiceConnected() {
        if (!bound) return
        registered = platform.register()
        if (!registered) {
            platform.log("register() refused - is Glyph debug mode enabled?")
            connection = State.FAILED
            if (visible) scheduleReconnect()
            return
        }
        retries = 0
        platform.cancelPosted()
        if (visible) openSession()
    }

    private fun onServiceDisconnected() {
        registered = false
        connection = State.CONNECTING
        if (visible) scheduleReconnect()
    }

    /** The app is on screen: opens a session (binding first, the first time). */
    fun enterForeground() {
        if (connection == State.UNSUPPORTED) return
        visible = true
        platform.watchGlobals(listOf(SYSTEM_SWITCH, SCREEN_UPWARD), ::readSystemSwitch)
        readSystemSwitch()
        when {
            !bound -> {
                bound = true
                bind()
                retries = 0
                scheduleReconnect() // in case this bind never connects
            }
            registered -> openSession()
            else -> {
                // Lost while we were away: try again now rather than after the back-off.
                retries = 0
                platform.cancelPosted()
                reconnect()
            }
        }
    }

    /** The app left the screen: lights off and session closed; the binding stays. */
    fun enterBackground() {
        if (visible) platform.unwatchGlobals()
        visible = false
        platform.cancelPosted()
        if (!bound) return
        if (connection != State.READY) return
        // Unbinding used to clear the lights too; staying bound, blank them explicitly.
        platform.setFrameColors(IntArray(GlyphZones.COUNT))
        platform.turnOff()
        platform.closeSession()
        // No session until the app is back; the next enterForeground opens one.
        connection = State.CONNECTING
    }

    private fun openSession() {
        connection = if (platform.openSession()) State.READY else State.FAILED
    }

    /** Shows one frame: [GlyphZones.COUNT] stored values in 0..[GlyphZones.MAX_BRIGHTNESS]. */
    fun show(frame: ShortArray) {
        if (!bound) return
        if (connection != State.READY) return
        for (i in 0 until GlyphZones.COUNT) {
            buffer[i] = frame[i].toInt() * SDK_MAX_BRIGHTNESS / GlyphZones.MAX_BRIGHTNESS
        }
        platform.setFrameColors(buffer)
    }

    fun off() {
        if (bound && connection == State.READY) platform.turnOff()
    }

    companion object {
        /**
         * Upper bound the SDK's setFrameColors() accepts. Undocumented; the system's own
         * ringtone data is 12 bit, so we assume the same range here.
         */
        const val SDK_MAX_BRIGHTNESS = 4095

        /** Nothing OS's Glyph Interface switch, which its Quick Settings tile and schedule set. */
        private const val SYSTEM_SWITCH = "led_effect_enable"

        /** Nothing OS's live orientation for the Glyphs; [SCREEN_UPWARD_UP] means lying screen-up. */
        private const val SCREEN_UPWARD = "glyph_screen_upward_state"
        private const val SCREEN_UPWARD_UP = 1

        private const val RETRY_FIRST_MS = 2_000L
        private const val RETRY_MAX_MS = 15_000L
    }
}
