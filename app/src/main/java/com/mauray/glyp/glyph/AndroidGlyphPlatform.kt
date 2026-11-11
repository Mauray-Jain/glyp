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

/** [GlyphPlatform] on a real phone: Nothing's `GlyphManager`, `Settings.Global` and the main looper. */
class AndroidGlyphPlatform(context: Context) : GlyphPlatform {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var manager: GlyphManager? = null
    private var posted: Runnable? = null
    private var observer: ContentObserver? = null
    private var onGlobalsChanged: () -> Unit = {}

    override val supported: Boolean = Common.is24111()

    override fun bind(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        // The SDK delivers these on the main thread, which is what GlyphController expects.
        val callback = object : GlyphManager.Callback {
            override fun onServiceConnected(name: ComponentName) = onConnected()
            override fun onServiceDisconnected(name: ComponentName) = onDisconnected()
        }
        val gm = manager ?: GlyphManager.getInstance(appContext).also { manager = it }
        gm.init(callback)
    }

    override fun unbind() {
        manager?.unInit()
    }

    override fun register(): Boolean = manager?.register(Glyph.DEVICE_24111) ?: false

    override fun openSession(): Boolean = try {
        manager?.openSession()
        true
    } catch (e: GlyphException) {
        Log.e(TAG, "openSession failed", e)
        false
    }

    override fun closeSession() {
        try {
            manager?.closeSession()
        } catch (e: GlyphException) {
            Log.w(TAG, "closeSession failed", e)
        }
    }

    override fun turnOff() {
        try {
            manager?.turnOff()
        } catch (e: GlyphException) {
            Log.w(TAG, "turnOff failed", e)
        }
    }

    override fun setFrameColors(colors: IntArray) {
        try {
            manager?.setFrameColors(colors)
        } catch (e: GlyphException) {
            Log.w(TAG, "setFrameColors failed", e)
        }
    }

    /** Not on this OS version, or not readable: null, so the controller says nothing rather than guess. */
    override fun readGlobal(name: String): Int? = try {
        Settings.Global.getInt(appContext.contentResolver, name)
    } catch (e: Exception) {
        null
    }

    override fun watchGlobals(names: List<String>, onChange: () -> Unit) {
        onGlobalsChanged = onChange
        val o = observer ?: object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) = onGlobalsChanged()
        }.also { observer = it }
        for (name in names) appContext.contentResolver.registerContentObserver(Settings.Global.getUriFor(name), false, o)
    }

    override fun unwatchGlobals() {
        observer?.let { appContext.contentResolver.unregisterContentObserver(it) }
    }

    override fun postDelayed(delayMs: Long, task: () -> Unit) {
        cancelPosted()
        posted = Runnable(task).also { main.postDelayed(it, delayMs) }
    }

    override fun cancelPosted() {
        posted?.let(main::removeCallbacks)
        posted = null
    }

    override fun log(message: String) {
        Log.i(TAG, message)
    }

    private companion object {
        const val TAG = "GlyphController"
    }
}
