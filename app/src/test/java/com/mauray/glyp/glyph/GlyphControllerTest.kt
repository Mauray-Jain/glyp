package com.mauray.glyp.glyph

import com.mauray.glyp.glyph.GlyphController.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlyphControllerTest {

    private class Rig(supported: Boolean = true) {
        val platform = FakeGlyphPlatform(supported)
        val controller = GlyphController(platform)
        val state get() = controller.state.value

        /** Foreground, service connected and registered: READY. */
        fun ready(): Rig {
            controller.enterForeground()
            platform.connect()
            return this
        }

        fun callsSince(mark: Int) = platform.calls.drop(mark)
    }

    private val zeros = IntArray(GlyphZones.COUNT).joinToString(",")

    @Test
    fun `an unsupported phone never binds`() {
        val r = Rig(supported = false)
        assertEquals(State.UNSUPPORTED, r.state)
        r.controller.enterForeground()
        assertFalse("bind" in r.platform.calls)
        assertEquals(State.UNSUPPORTED, r.state)
    }

    @Test
    fun `the first foreground binds once and waits two seconds before retrying`() {
        val r = Rig()
        r.controller.enterForeground()
        assertEquals(1, r.platform.calls.count { it == "bind" })
        assertEquals(State.CONNECTING, r.state)
        assertEquals(2_000L, r.platform.pendingDelayMs)
    }

    @Test
    fun `a connected and registered service opens a session`() {
        val r = Rig().ready()
        assertTrue("openSession" in r.platform.calls)
        assertEquals(State.READY, r.state)
        assertNull("the retry was cancelled", r.platform.pendingDelayMs)
    }

    @Test
    fun `going to the background blanks the lights and closes the session but stays bound`() {
        val r = Rig().ready()
        val mark = r.platform.calls.size
        r.controller.enterBackground()
        assertEquals(listOf("unwatchGlobals", "setFrameColors:$zeros", "turnOff", "closeSession"), r.callsSince(mark))
        assertEquals(State.CONNECTING, r.state)
        assertFalse("unbind" in r.platform.calls)
    }

    @Test
    fun `coming back opens a new session without binding again`() {
        val r = Rig().ready()
        r.controller.enterBackground()
        val mark = r.platform.calls.size
        r.controller.enterForeground()
        assertTrue("openSession" in r.callsSince(mark))
        assertEquals(1, r.platform.calls.count { it == "bind" })
        assertEquals(State.READY, r.state)
    }

    @Test
    fun `a refused registration fails and keeps retrying while visible`() {
        val r = Rig()
        r.platform.registerResult = false
        r.controller.enterForeground()
        r.platform.connect()
        assertEquals(State.FAILED, r.state)
        assertTrue(r.platform.pendingDelayMs != null)
    }

    @Test
    fun `a lost binding rebinds with a capped back-off`() {
        val r = Rig().ready()
        r.platform.disconnect()
        assertEquals(State.CONNECTING, r.state)
        val delays = mutableListOf(r.platform.pendingDelayMs!!)
        val mark = r.platform.calls.size
        r.platform.runPending()
        assertEquals(listOf("unbind", "bind"), r.callsSince(mark))
        repeat(4) {
            delays += r.platform.pendingDelayMs!!
            if (it < 3) r.platform.runPending()
        }
        assertEquals(listOf(2_000L, 4_000L, 8_000L, 15_000L, 15_000L), delays)
    }

    @Test
    fun `the retry does nothing once the app has left the screen`() {
        val r = Rig()
        r.controller.enterForeground()
        val retry = r.platform.lastPosted!!
        r.controller.enterBackground()
        val mark = r.platform.calls.size
        retry()
        assertTrue("nothing bound or unbound: ${r.callsSince(mark)}", r.callsSince(mark).isEmpty())
    }

    @Test
    fun `a binding lost in the background is retried at once on return`() {
        val r = Rig().ready()
        r.controller.enterBackground()
        r.platform.disconnect()
        assertNull("no retry while away", r.platform.pendingDelayMs)
        val mark = r.platform.calls.size
        r.controller.enterForeground()
        val since = r.callsSince(mark)
        assertTrue(since.indexOf("unbind") in 0 until since.indexOf("bind"))
        assertEquals(2_000L, r.platform.pendingDelayMs)
    }

    @Test
    fun `Nothing OS switches decide the state while connected`() {
        val r = Rig()
        fun stateWith(switch: Int?, upward: Int?): State {
            r.platform.globals["led_effect_enable"] = switch
            r.platform.globals["glyph_screen_upward_state"] = upward
            r.platform.onGlobalsChange?.invoke()
            return r.state
        }
        r.ready()
        assertEquals(State.SWITCHED_OFF, stateWith(0, null))
        assertEquals(State.SCREEN_UP, stateWith(1, 1))
        assertEquals("the switch wins", State.SWITCHED_OFF, stateWith(0, 1))
        assertEquals(State.READY, stateWith(null, null))
        assertEquals(State.READY, stateWith(1, 0))
    }

    @Test
    fun `frames are sent only while a session is open, at full scale`() {
        val r = Rig()
        r.controller.enterForeground()
        r.controller.show(ShortArray(GlyphZones.COUNT) { 4095 })
        assertTrue("nothing while CONNECTING", r.platform.frames.isEmpty())
        r.platform.connect()
        val full = ShortArray(GlyphZones.COUNT) { 4095 }
        for ((switch, upward) in listOf(1 to 0, 0 to 0, 1 to 1)) {
            r.platform.globals["led_effect_enable"] = switch
            r.platform.globals["glyph_screen_upward_state"] = upward
            r.platform.onGlobalsChange?.invoke()
            r.platform.frames.clear()
            r.controller.show(full)
            r.controller.show(ShortArray(GlyphZones.COUNT))
            assertEquals("in ${r.state}", List(GlyphZones.COUNT) { 4095 }, r.platform.frames[0].toList())
            assertEquals("in ${r.state}", List(GlyphZones.COUNT) { 0 }, r.platform.frames[1].toList())
        }
    }

    @Test
    fun `off turns the lights off only while connected`() {
        val r = Rig()
        r.controller.enterForeground()
        r.controller.off()
        assertFalse("turnOff" in r.platform.calls)
        r.platform.connect()
        for (switch in listOf(1, 0)) {
            r.platform.globals["led_effect_enable"] = switch
            r.platform.onGlobalsChange?.invoke()
            val mark = r.platform.calls.size
            r.controller.off()
            assertEquals("in ${r.state}", listOf("turnOff"), r.callsSince(mark))
        }
        r.platform.globals["led_effect_enable"] = 1
        r.platform.globals["glyph_screen_upward_state"] = 1
        r.platform.onGlobalsChange?.invoke()
        assertEquals(State.SCREEN_UP, r.state)
        val mark = r.platform.calls.size
        r.controller.off()
        assertEquals(listOf("turnOff"), r.callsSince(mark))
    }
}
