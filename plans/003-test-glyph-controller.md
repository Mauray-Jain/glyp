# Plan 003: GlyphController's connection logic is covered by JVM unit tests

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- app/src/main/java/com/mauray/glyp/glyph/ app/src/main/java/com/mauray/glyp/GlypApp.kt app/src/test/java/com/mauray/glyp/glyph/`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: M
- **Risk**: MED (refactor of the code that drives the phone's lights; behaviour must not change)
- **Depends on**: none (plan 002's CI makes regressions visible sooner)
- **Category**: tests
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

`GlyphController` talks to Nothing's Glyph SDK so the app can light the back of a Nothing
Phone (3a). On one day it was changed three times to fix real field bugs: it got stuck on
"CONNECTING" forever when a bind was lost, then learned to bind once per process and only
open/close a *session* with visibility, then learned to report when Nothing OS has the Glyphs
switched off (`SWITCHED_OFF`) or the phone lies screen-up (`SCREEN_UP`). It has **zero tests**,
because it calls Android (`Handler`, `ContentObserver`, `Settings.Global`, `Log`) and the SDK
(`GlyphManager`) directly, and those throw "Method … not mocked" on the plain JVM. Every one of
those fixes was verified only by hand on a phone. This plan moves the platform calls behind a
small interface so the state machine can be tested exhaustively with a fake, **without changing
behaviour**.

## Current state

- `app/src/main/java/com/mauray/glyp/glyph/GlyphController.kt` (214 lines) — the class to refactor.
  Public API used elsewhere (must stay exactly as is):
  - `class GlyphController(context: Context)` — constructed in `GlypApp.kt`:
    `val glyph by lazy { GlyphController(this) }`
  - `enum class State { UNSUPPORTED, CONNECTING, READY, SWITCHED_OFF, SCREEN_UP, FAILED }` —
    referenced as `GlyphController.State` in `ui/studio/StudioScreen.kt` and
    `app/src/test/java/com/mauray/glyp/ui/ScreenshotTest.kt`.
  - `val state: StateFlow<State>`, `fun enterForeground()`, `fun enterBackground()` (called from
    `MainActivity.onStart/onStop`), `fun show(frame: ShortArray)`, `fun off()`.
- Key excerpts (line numbers at `f1c86f3`):
  ```kotlin
  // 35
  private var connection = if (Common.is24111()) State.CONNECTING else State.UNSUPPORTED
      set(value) { field = value; publish() }
  // 47
  private fun publish() {
      _state.value = when {
          connection != State.READY -> connection
          systemOn == false -> State.SWITCHED_OFF
          screenUp == true -> State.SCREEN_UP
          else -> connection
      }
  }
  // 69
  private val reconnect: Runnable = Runnable {
      val gm = manager ?: return@Runnable
      if (registered || !visible) return@Runnable
      Log.i(TAG, "Glyph service not connected; binding again")
      gm.unInit()
      gm.init(callback)
      scheduleReconnect()
  }
  // 97
  private fun scheduleReconnect() {
      main.removeCallbacks(reconnect)
      main.postDelayed(reconnect, minOf(RETRY_FIRST_MS shl minOf(retries++, 4), RETRY_MAX_MS))
  }
  // 102: callback.onServiceConnected: registered = gm.register(Glyph.DEVICE_24111);
  //      if !registered -> connection = FAILED, schedule reconnect if visible, return;
  //      else retries = 0, cancel reconnect, openSession if visible.
  // 117: callback.onServiceDisconnected: registered = false; connection = CONNECTING;
  //      schedule reconnect if visible.
  // 125: enterForeground(): if UNSUPPORTED return; visible = true; register two
  //      ContentObservers (SYSTEM_SWITCH, SCREEN_UPWARD); readSystemSwitch();
  //      first time: GlyphManager.getInstance(ctx).init(callback), retries = 0, scheduleReconnect();
  //      already registered: openSession; else: retries = 0, cancel, run reconnect now.
  // 149: enterBackground(): unregister observer if visible; visible = false; cancel reconnect;
  //      if connection == READY: setFrameColors(zeros), turnOff(), closeSession() (GlyphException
  //      logged), then connection = CONNECTING.
  // 178: show(): only when connection == READY; scales each value
  //      buffer[i] = frame[i] * SDK_MAX_BRIGHTNESS / GlyphZones.MAX_BRIGHTNESS; setFrameColors.
  // 191: off(): turnOff() only when connection == READY.
  // constants: SYSTEM_SWITCH = "led_effect_enable" (0 = Glyphs off),
  //   SCREEN_UPWARD = "glyph_screen_upward_state" (1 = lying screen-up),
  //   RETRY_FIRST_MS = 2_000L, RETRY_MAX_MS = 15_000L.
  ```
  Read the whole file before starting; the excerpt above is a map, not a substitute.
- Test conventions: JUnit 4 + `kotlinx-coroutines-test`. Fakes live next to the tests — e.g.
  `app/src/test/java/com/mauray/glyp/studio/FakeClock.kt` fakes the `Clock` interface for
  `TakeRecorderTest`. Follow that shape: an interface in main, a fake in test.
- Comment style: short `/** KDoc */` on public types and non-obvious members, `//` comments
  explaining *why*. Match it.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Unit tests (all) | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |
| Just the new tests | `./gradlew :app:testDebugUnitTest --tests '*GlyphControllerTest*'` | `BUILD SUCCESSFUL` |
| Screenshot check | `./gradlew :app:verifyPaparazziDebug` | `BUILD SUCCESSFUL` |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |

(Needs JDK 17 unless plan 001 landed; needs `./scripts/fetch-glyph-sdk.sh` once.)

## Scope

**In scope**:
- `app/src/main/java/com/mauray/glyp/glyph/GlyphController.kt` (refactor)
- `app/src/main/java/com/mauray/glyp/glyph/GlyphPlatform.kt` (create: interface)
- `app/src/main/java/com/mauray/glyp/glyph/AndroidGlyphPlatform.kt` (create: the Android/SDK adapter)
- `app/src/test/java/com/mauray/glyp/glyph/FakeGlyphPlatform.kt` (create)
- `app/src/test/java/com/mauray/glyp/glyph/GlyphControllerTest.kt` (create)

**Out of scope**:
- Any behaviour change, however tempting. If you find a bug, write it down in your report.
- `MainActivity.kt`, `GlypApp.kt`, `StudioScreen.kt` — the public API must not change, so these
  need no edits. If they need edits, you changed the API: STOP.
- `GlyphZones.kt`.

## Git workflow

- Branch: `advisor/003-test-glyph-controller`
- Two commits: (1) introduce `GlyphPlatform` + adapter with no behaviour change, (2) add tests.
- Commit message: imperative summary + "why" paragraph, as in `git log`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Define the platform seam

Create `GlyphPlatform.kt`:

```kotlin
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
    fun turnOff()
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
```

**Verify**: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.

### Step 2: Move every Android/SDK call into `AndroidGlyphPlatform`

Create `AndroidGlyphPlatform.kt` implementing `GlyphPlatform` with the code that is in
`GlyphController` today: `GlyphManager.getInstance(context).init(callback)` / `unInit()`,
`register(Glyph.DEVICE_24111)`, `openSession()` inside `try/catch (GlyphException)`,
`Handler(Looper.getMainLooper())` for `postDelayed`/`cancelPosted` (keep a reference to the
posted `Runnable` so `cancelPosted` can remove it), a `ContentObserver(handler)` registered via
`Settings.Global.getUriFor(name)` for each name in `watchGlobals`, `Settings.Global.getInt` in
`readGlobal` returning null on any `Exception`, and `Log` with the existing `TAG`. Keep the
existing comments that explain *why* (e.g. "Unbinding used to clear the lights too…") with the code
they describe.

**Verify**: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.

### Step 3: Make `GlyphController` use the seam

Give `GlyphController` a primary constructor `GlyphController(private val platform: GlyphPlatform)`
and a secondary `constructor(context: Context) : this(AndroidGlyphPlatform(context))`, so
`GlypApp.kt` compiles unchanged. Replace every direct Android/SDK call with the matching
`platform` call. After this step, `GlyphController.kt` must not import anything from
`android.*` or `com.nothing.*`.

Keep the logic line-for-line equivalent: same `connection`/`publish()` rules, same
`retries` back-off (`minOf(RETRY_FIRST_MS shl minOf(retries++, 4), RETRY_MAX_MS)`), same guards
(`registered`, `visible`, `connection == READY`), same zero frame before `turnOff()` +
`closeSession()` in `enterBackground()`.

**Verify**:
- `grep -n -E "^import (android|com\.nothing)" app/src/main/java/com/mauray/glyp/glyph/GlyphController.kt` → no output
- `./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug assembleDebug` → `BUILD SUCCESSFUL`
- `git diff --stat -- app/src/main/java/com/mauray/glyp/GlypApp.kt app/src/main/java/com/mauray/glyp/MainActivity.kt` → empty

### Step 4: Write the fake

`FakeGlyphPlatform.kt` in `app/src/test/java/com/mauray/glyp/glyph/`: records calls in a
`MutableList<String>` (e.g. `"bind"`, `"unbind"`, `"openSession"`, `"setFrameColors:0,0,…"`),
exposes `registerResult`, `openSessionResult`, a `globals: MutableMap<String, Int?>`, the stored
`onConnected`/`onDisconnected` callbacks, the watched `onChange`, and the pending task with its
delay (`pendingDelayMs: Long?`, `fun runPending()`). `supported` is a constructor parameter.

**Verify**: `./gradlew :app:compileDebugUnitTestKotlin` → `BUILD SUCCESSFUL`.

### Step 5: Write the tests

`GlyphControllerTest.kt` — one test per behaviour below. These describe today's behaviour; if a
test fails, the refactor changed something (fix the refactor, not the test).

1. Unsupported device: state `UNSUPPORTED`; `enterForeground()` does not `bind`.
2. First `enterForeground()`: one `bind`, state `CONNECTING`, a task pending at 2000 ms.
3. Connected + `register()` true while visible: `openSession`, state `READY`, pending task cancelled.
4. `enterBackground()` when `READY`: calls in order `setFrameColors` with 36 zeros, `turnOff`,
   `closeSession`; state `CONNECTING`; `unwatchGlobals` called; **no** `unbind`.
5. Second `enterForeground()` after 4: `openSession` again, **no** second `bind`, state `READY`.
6. `register()` false: state `FAILED`, task pending while visible.
7. Disconnected while visible: state `CONNECTING`; running the pending task calls `unbind` then
   `bind`; successive pending delays are 2000, 4000, 8000, 15000, 15000 (back-off capped).
8. The pending task does nothing when the app is not visible (call `enterBackground()` first).
9. `enterForeground()` after the bind was lost in the background: immediate `unbind` + `bind`
   (no waiting), and a new task pending at 2000 ms.
10. Globals: `led_effect_enable = 0` → `SWITCHED_OFF`; `glyph_screen_upward_state = 1` → `SCREEN_UP`;
    both → `SWITCHED_OFF` (switch wins); both null → `READY`; a change delivered through the
    watched `onChange` updates `state` without re-entering the foreground.
11. `show()`: no `setFrameColors` while `CONNECTING`; while `READY`, `SWITCHED_OFF` or
    `SCREEN_UP` a frame of `4095`s is sent as `4095`s and `0`s as `0`s.
12. `off()`: `turnOff` only when connected (`READY`, `SWITCHED_OFF` or `SCREEN_UP`).

Read `state.value` directly (it is a `StateFlow`; no coroutines needed).

**Verify**: `./gradlew :app:testDebugUnitTest --tests '*GlyphControllerTest*'` → `BUILD SUCCESSFUL`, 12+ tests.

## Test plan

The 12 cases above in `GlyphControllerTest.kt`, using `FakeGlyphPlatform`. Pattern to imitate:
`app/src/test/java/com/mauray/glyp/studio/TakeRecorderTest.kt` (a `Rig`/helper building the
subject with its fake, one behaviour per `@Test`, backtick names in plain English).

## Done criteria

- [ ] `./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug assembleDebug` → `BUILD SUCCESSFUL`
- [ ] `GlyphControllerTest` has at least 12 tests, all passing
- [ ] `GlyphController.kt` has no `android.*` or `com.nothing.*` imports
- [ ] `GlypApp.kt`, `MainActivity.kt`, `StudioScreen.kt` unchanged (`git diff --stat` on them is empty)
- [ ] `git status --short` lists only in-scope files
- [ ] `plans/README.md` status row updated

## STOP conditions

- A test from step 5 can only pass by changing behaviour — report the case and the behaviour you
  found instead.
- Keeping `GlyphController.State` and the public methods unchanged turns out to be impossible.
- The SDK's `GlyphManager.Callback` delivers on a thread other than main in a way the seam can't
  express (it is main today; the adapter must keep it so).

## Maintenance notes

- Any future Glyph behaviour (e.g. respecting another Nothing OS setting) should arrive as a
  `readGlobal` name plus a test case here, not as a new direct Android call in the controller.
- If someone hand-tests on a phone after this lands, the paths that matter are: Home and back,
  lock/unlock, opening the file picker, toggling the Glyphs tile, laying the phone screen-up.
  Plan 014 scripts most of these.
