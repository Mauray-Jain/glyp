# CLAUDE.md

Glyp is an Android app (Kotlin, Jetpack Compose, single module `:app`) that turns songs into
light compositions for the **Nothing Phone (3a) / (3a) Pro** Glyph lights and exports them as Glyph
ringtones (Ogg Opus with light data in the tags). Read `README.md` for features, `GLOSSARY.md` for
the words the code uses (take, light document, phase…), and `docs/adr/` for decisions — don't
"fix" something an ADR chose on purpose.

## Build and verify

- **JDK 17.** Gradle 8.14 can't start on much newer JDKs (the error is just a bare version string,
  e.g. `27.0.1.0.3`). Until `gradle/gradle-daemon-jvm.properties` exists (plan 001), prefix
  commands with `JAVA_HOME=<a JDK 17>`.
- **Glyph SDK** is not in git (licence): run `./scripts/fetch-glyph-sdk.sh` once; the build fails
  fast without it.
- Gate before saying something works:
  ```bash
  ./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug assembleDebug
  ```
  Count tests: `grep -h -o 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml`.
- **Screenshots (Paparazzi):** goldens are in `app/src/test/snapshots/images/`. If
  `verifyPaparazziDebug` fails after a UI change, run `:app:recordPaparazziDebug`, then **look at
  every changed PNG** (old one: `git show HEAD:<path>`) before committing — a re-record caught a
  clipped label once.
- Lint: `./gradlew :app:lintDebug` → `app/build/reports/lint-results-debug.txt`.

## Code conventions

- Match the surrounding style: short KDoc on public types, `//` comments that say *why*, plain-English
  backtick test names, one behaviour per `@Test`.
- Platform code sits behind small interfaces with fakes in tests (`studio/Clock.kt` +
  `FakeClock`); keep logic out of Android classes so it runs on the JVM.
- `LightDocument` is confined to the main thread (checked at runtime). Work from other threads
  reports back through a thread-safe value and is applied on the session's thread — never touch the
  document from a callback that runs on `Dispatchers.IO`/`Default` (that bug broke every export once).
- Every light value goes through `LightShapes.stored(level)` (gamma 1.6, 0..4095).
- Commit messages: imperative summary line, blank line, a short paragraph on *why*.

## Testing on the phone (adb)

- Package `com.mauray.glyp`, launch `adb shell am start -W -n com.mauray.glyp/.MainActivity`.
- Drive the UI with `adb shell uiautomator dump` + tap element centres; prefer
  `adb shell "input motionevent DOWN x y; …; input motionevent UP x y"` for precise holds and drags
  (`input swipe` holds longer than its duration). The first tap right after a launch is sometimes
  lost — retry.
- Use **bash**, not zsh, for helper scripts: zsh reads `$var:s…` as a modifier; write `${var}`.
- **What reaches the Glyphs:** `adb logcat -c`, play, then
  `adb logcat -d | grep -c 'GlyphService.*finalColors'` (Nothing's service logs every frame).
  Frames reaching the service ≠ lights on: check the status label and the two Nothing settings below.
- **Studio status label:** `● LIVE`, `CONNECTING`, `GLYPHS OFF` (Nothing OS has the Glyph Interface
  off — Quick Settings tile or its **bedtime schedule**), `SCREEN UP` (phone lying screen-up; Nothing
  OS keeps the Glyphs dark), `NO GLYPH ACCESS`, `PREVIEW` (not a Phone (3a)).
- Read-only diagnostics: `adb shell settings get global led_effect_enable` (0 = Glyphs off),
  `adb shell settings get global glyph_screen_upward_state` (1 = screen-up).
- Glyp binds to the Glyph service **once per process**; Home/back should not log a new
  `bindService … client com.mauray.glyp`. A stuck `CONNECTING` means a lost bind (the watchdog
  re-binds within 2–15 s).
- Projects live in app storage: `adb shell run-as com.mauray.glyp ls files/projects`
  (`project.json`, `lights.bin` = big-endian int frame count + 36 shorts per frame, audio copy).
- Exports: `/sdcard/Ringtones/Glyp/*.ogg`; check tags with
  `ffprobe -v error -show_entries stream_tags=COMPOSER -of csv=p=0 <file>` →
  `v1-Asteroids Glyph Composer`.

### Don't

- Don't change the user's phone settings (Glyphs tile, schedule, ringtone, debug mode, stay-awake)
  without asking, and restore anything you change. Never enter a PIN.
- Don't delete user projects or ringtones; create test data with a recognisable prefix (e.g. `gt-`)
  and clean up only that.
- Don't count a check as done when the screen was in a different state than you assumed
  (lost tap, project not opened) — re-read the screen before trusting a result.

## Limits worth knowing

- Songs up to **10 minutes** (memory: the whole song is decoded; ~185 MB heap at 10 min of the
  256 MB limit). See ADR 0005 and its update.
- Exporting a name again **replaces** that ringtone in place (same MediaStore URI).

## Plans

`plans/` holds self-contained improvement plans (index: `plans/README.md`). When you complete one,
update its status row there.
