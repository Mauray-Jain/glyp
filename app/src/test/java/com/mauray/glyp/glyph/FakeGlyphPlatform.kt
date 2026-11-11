package com.mauray.glyp.glyph

/** Records what [GlyphController] asks of the platform and lets tests play the service's part. */
class FakeGlyphPlatform(override val supported: Boolean = true) : GlyphPlatform {

    val calls = mutableListOf<String>()
    var registerResult = true
    var openSessionResult = true
    val globals = mutableMapOf<String, Int?>()

    var onConnected: (() -> Unit)? = null
        private set
    var onDisconnected: (() -> Unit)? = null
        private set
    var onGlobalsChange: (() -> Unit)? = null
        private set

    /** The pending reconnect and its delay; null when nothing is posted. */
    var pendingDelayMs: Long? = null
        private set
    private var pending: (() -> Unit)? = null

    /** The last task posted, kept even after it was cancelled, to run a stale one on purpose. */
    var lastPosted: (() -> Unit)? = null
        private set

    /** Every delay ever posted, in order. */
    val postedDelays = mutableListOf<Long>()

    val frames = mutableListOf<IntArray>()

    fun connect() = onConnected!!()
    fun disconnect() = onDisconnected!!()

    fun runPending() {
        val task = pending ?: error("nothing pending")
        pending = null
        pendingDelayMs = null
        task()
    }

    override fun bind(onConnected: () -> Unit, onDisconnected: () -> Unit) {
        calls += "bind"
        this.onConnected = onConnected
        this.onDisconnected = onDisconnected
    }

    override fun unbind() { calls += "unbind" }

    override fun register(): Boolean {
        calls += "register"
        return registerResult
    }

    override fun openSession(): Boolean {
        calls += "openSession"
        return openSessionResult
    }

    override fun closeSession() { calls += "closeSession" }

    override fun turnOff() { calls += "turnOff" }

    override fun setFrameColors(colors: IntArray) {
        calls += "setFrameColors:" + colors.joinToString(",")
        frames += colors.copyOf()
    }

    override fun readGlobal(name: String): Int? = globals[name]

    override fun watchGlobals(names: List<String>, onChange: () -> Unit) {
        calls += "watchGlobals"
        onGlobalsChange = onChange
    }

    override fun unwatchGlobals() { calls += "unwatchGlobals" }

    override fun postDelayed(delayMs: Long, task: () -> Unit) {
        pending = task
        lastPosted = task
        pendingDelayMs = delayMs
        postedDelays += delayMs
    }

    override fun cancelPosted() {
        pending = null
        pendingDelayMs = null
    }

    override fun log(message: String) {}
}
