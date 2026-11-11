package com.mauray.glyp.glyph

import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.nothing.ketchum.Common
import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphException
import com.nothing.ketchum.GlyphManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thin wrapper around Nothing's GlyphManager that drives all 36 zones of the Phone (3a)
 * with per-zone brightness. On any other device every call is a no-op, so the rest of
 * the app (editor, export) still works and only the live preview on the back is missing.
 */
class GlyphController(context: Context) {

    /**
     * [SWITCHED_OFF]: connected, but Nothing OS has the Glyph Interface off (its toggle or
     * schedule). [SCREEN_UP]: connected, but the phone lies screen-up, and Nothing OS keeps
     * the Glyphs dark then since they face the table.
     */
    enum class State { UNSUPPORTED, CONNECTING, READY, SWITCHED_OFF, SCREEN_UP, FAILED }

    private val appContext = context.applicationContext

    /** The connection's own state; [state] also says when the system switch is off. */
    private var connection = if (Common.is24111()) State.CONNECTING else State.UNSUPPORTED
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
    private var manager: GlyphManager? = null
    private var registered = false
    private var visible = false
    private val buffer = IntArray(GlyphZones.COUNT)

    // The SDK never rebinds on its own: if the binding dies, or a bind never connects, the
    // app would stay CONNECTING with the lights dark. So while visible and not registered,
    // bind again, backing off.
    private val main = Handler(Looper.getMainLooper())
    private var retries = 0
    private val reconnect: Runnable = Runnable {
        val gm = manager ?: return@Runnable
        if (registered || !visible) return@Runnable
        Log.i(TAG, "Glyph service not connected; binding again")
        gm.unInit()
        gm.init(callback)
        scheduleReconnect()
    }

    // Watched while visible, so the status follows the Glyphs tile, the schedule and the
    // phone being laid screen-up as they change.
    private val systemSwitch = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) = readSystemSwitch()
    }

    private fun readSystemSwitch() {
        systemOn = readGlobal(SYSTEM_SWITCH)?.let { it != 0 }
        screenUp = readGlobal(SCREEN_UPWARD)?.let { it == SCREEN_UPWARD_UP }
        publish()
    }

    /** Not on this OS version, or not readable: null, so we say nothing rather than guess. */
    private fun readGlobal(name: String): Int? = try {
        Settings.Global.getInt(appContext.contentResolver, name)
    } catch (e: Exception) {
        null
    }

    private fun scheduleReconnect() {
        main.removeCallbacks(reconnect)
        main.postDelayed(reconnect, minOf(RETRY_FIRST_MS shl minOf(retries++, 4), RETRY_MAX_MS))
    }

    private val callback: GlyphManager.Callback = object : GlyphManager.Callback {
        override fun onServiceConnected(name: ComponentName) {
            val gm = manager ?: return
            registered = gm.register(Glyph.DEVICE_24111)
            if (!registered) {
                Log.w(TAG, "register() refused - is Glyph debug mode enabled?")
                connection = State.FAILED
                if (visible) scheduleReconnect()
                return
            }
            retries = 0
            main.removeCallbacks(reconnect)
            if (visible) openSession(gm)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            registered = false
            connection = State.CONNECTING
            if (visible) scheduleReconnect()
        }
    }

    /** The app is on screen: opens a session (binding first, the first time). */
    fun enterForeground() {
        if (connection == State.UNSUPPORTED) return
        visible = true
        appContext.contentResolver.registerContentObserver(Settings.Global.getUriFor(SYSTEM_SWITCH), false, systemSwitch)
        appContext.contentResolver.registerContentObserver(Settings.Global.getUriFor(SCREEN_UPWARD), false, systemSwitch)
        readSystemSwitch()
        val gm = manager
        when {
            gm == null -> {
                manager = GlyphManager.getInstance(appContext).also { it.init(callback) }
                retries = 0
                scheduleReconnect() // in case this bind never connects
            }
            registered -> openSession(gm)
            else -> {
                // Lost while we were away: try again now rather than after the back-off.
                retries = 0
                main.removeCallbacks(reconnect)
                reconnect.run()
            }
        }
    }

    /** The app left the screen: lights off and session closed; the binding stays. */
    fun enterBackground() {
        if (visible) appContext.contentResolver.unregisterContentObserver(systemSwitch)
        visible = false
        main.removeCallbacks(reconnect)
        val gm = manager ?: return
        if (connection != State.READY) return
        try {
            // Unbinding used to clear the lights too; staying bound, blank them explicitly.
            gm.setFrameColors(IntArray(GlyphZones.COUNT))
            gm.turnOff()
            gm.closeSession()
        } catch (e: GlyphException) {
            Log.w(TAG, "closeSession failed", e)
        }
        // No session until the app is back; the next enterForeground opens one.
        connection = State.CONNECTING
    }

    private fun openSession(gm: GlyphManager) {
        try {
            gm.openSession()
            connection = State.READY
        } catch (e: GlyphException) {
            Log.e(TAG, "openSession failed", e)
            connection = State.FAILED
        }
    }

    /** Shows one frame: [GlyphZones.COUNT] stored values in 0..[GlyphZones.MAX_BRIGHTNESS]. */
    fun show(frame: ShortArray) {
        val gm = manager ?: return
        if (connection != State.READY) return
        for (i in 0 until GlyphZones.COUNT) {
            buffer[i] = frame[i].toInt() * SDK_MAX_BRIGHTNESS / GlyphZones.MAX_BRIGHTNESS
        }
        try {
            gm.setFrameColors(buffer)
        } catch (e: GlyphException) {
            Log.w(TAG, "setFrameColors failed", e)
        }
    }

    fun off() {
        if (connection == State.READY) manager?.turnOff()
    }

    companion object {
        private const val TAG = "GlyphController"

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
