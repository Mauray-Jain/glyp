package com.mauray.glyphstudio.glyph

import android.content.ComponentName
import android.content.Context
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

    enum class State { UNSUPPORTED, CONNECTING, READY, FAILED }

    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(if (Common.is24111()) State.CONNECTING else State.UNSUPPORTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private var manager: GlyphManager? = null
    private val buffer = IntArray(GlyphZones.COUNT)

    private val callback = object : GlyphManager.Callback {
        override fun onServiceConnected(name: ComponentName) {
            val gm = manager ?: return
            if (!gm.register(Glyph.DEVICE_24111)) {
                Log.w(TAG, "register() refused - is Glyph debug mode enabled?")
                _state.value = State.FAILED
                return
            }
            try {
                gm.openSession()
                _state.value = State.READY
            } catch (e: GlyphException) {
                Log.e(TAG, "openSession failed", e)
                _state.value = State.FAILED
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            _state.value = State.CONNECTING
        }
    }

    fun start() {
        if (_state.value == State.UNSUPPORTED || manager != null) return
        manager = GlyphManager.getInstance(appContext).also { it.init(callback) }
    }

    fun stop() {
        val gm = manager ?: return
        try {
            gm.turnOff()
            gm.closeSession()
        } catch (e: GlyphException) {
            Log.w(TAG, "closeSession failed", e)
        }
        gm.unInit()
        manager = null
        if (_state.value != State.UNSUPPORTED) _state.value = State.CONNECTING
    }

    /** Shows one frame: [GlyphZones.COUNT] stored values in 0..[GlyphZones.MAX_BRIGHTNESS]. */
    fun show(frame: ShortArray) {
        val gm = manager ?: return
        if (_state.value != State.READY) return
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
        if (_state.value == State.READY) manager?.turnOff()
    }

    companion object {
        private const val TAG = "GlyphController"

        /**
         * Upper bound the SDK's setFrameColors() accepts. Undocumented; the system's own
         * ringtone data is 12 bit, so we assume the same range here.
         */
        const val SDK_MAX_BRIGHTNESS = 4095
    }
}
