# Plan 014: One command checks the app end to end on a connected Nothing Phone (3a)

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- scripts/ app/src/main/java/com/mauray/glyp/ui/`
> UI text changes (labels, button names) matter here because the script finds elements by their
> text. If any in-scope file changed, re-read the labels listed under "Current state" in the code.

## Status

- **Priority**: P2
- **Effort**: S–M
- **Risk**: LOW (a new script; no app code changes)
- **Depends on**: none
- **Category**: direction (dx)
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

Much of Glyp's behaviour only exists on the phone: the Glyph lights, Nothing OS's Glyph service and
switches, MediaStore exports, the system file picker. Unit tests can't see any of it, so every fix
so far was verified by hand-driving the phone over `adb`, with a throwaway helper rewritten each
session. A committed script that installs the app, imports a generated song, plays it, checks that
frames reach the Glyph service, backgrounds and resumes, and exports, turns "did I break the lights?"
into one command — usable by humans, agents (plans 003, 009, 010 reference it) and later CI with a
device.

## Current state

Facts the script relies on (all observed on a Phone (3a), model `A059`, package `com.mauray.glyp`):

- Launch: `adb shell am start -W -n com.mauray.glyp/.MainActivity`.
- UI text (find elements with `adb shell uiautomator dump /sdcard/ui.xml` then read the XML; match
  `text="…"` or `content-desc="…"` and tap the centre of `bounds="[x0,y0][x1,y1]"`):
  - Home: title `Glyp`; add button content-desc `New from song`; start sheet options
    `Auto compose` / `Start blank`; list section `Compositions`.
  - Studio: tabs `Compose`, `Edit`, `Record`; transport `Play` / `Pause`; back content-desc `Back`;
    export content-desc `Export ringtone`; export dialog buttons `Export`, then title `Saved` with
    `Done` / `Set as ringtone`.
  - Glyph status label (top right of the Studio): one of `● LIVE`, `CONNECTING`, `GLYPHS OFF`,
    `SCREEN UP`, `NO GLYPH ACCESS`, `PREVIEW`.
- System file picker (`com.google.android.documentsui`): has a content-desc `Search` button; type
  with `adb shell input text <query>` then `adb shell input keyevent 66`; results show the file name
  as text.
- Glyph frames: Nothing's system service logs every frame it receives as
  `D/GlyphService(<pid>): finalColors:[…36 values…]` — count with
  `adb logcat -d -v brief | grep -c 'GlyphService.*finalColors'` after `adb logcat -c`.
- Binding: Android's service tracker logs
  `bindService is called for service : com.nothing.thirdparty/.GlyphService and for client com.mauray.glyp`
  — the app should bind **once per process**; Home-and-back must not log another bind.
- Nothing OS switches (read-only!): `adb shell settings get global led_effect_enable` (0 = Glyphs
  switched off by tile/schedule), `adb shell settings get global glyph_screen_upward_state` (1 =
  phone lying screen-up). When either applies, frames still reach the service but the LEDs stay dark
  — the script must **warn, not fail**, and must never change these settings.
- Exports land in `/sdcard/Ringtones/Glyp/<name>.ogg`; verify with
  `ffprobe -v error -show_entries stream_tags=COMPOSER -of csv=p=0 <file>` →
  `v1-Asteroids Glyph Composer`.
- Pitfalls seen while hand-driving (build them into the script):
  - Write it in **bash** (`#!/usr/bin/env bash`, `set -euo pipefail`); zsh mis-parses `$var:s…`
    in strings — always use `${var}`.
  - `uiautomator dump` occasionally fails or returns a stale screen; retry a dump/tap up to 3 times
    with a 1 s pause before declaring "not found".
  - Generating a long tone with ffmpeg `aevalsrc` is slow — generate 10 s and loop it if more is
    needed; bound durations with `-t`.
  - Background jobs on the host must not poll with bare `sleep` loops longer than needed; keep
    total runtime under ~2 minutes.

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Phone attached | `adb devices` | one line ending in `device` |
| Debug APK | `./gradlew assembleDebug` | `BUILD SUCCESSFUL`, APK at `app/build/outputs/apk/debug/app-debug.apk` |
| Run the script | `scripts/device-smoke.sh` | last line `ALL CHECKS PASSED` |
| Script lint (if installed) | `shellcheck scripts/device-smoke.sh` | no output |

## Scope

**In scope**: `scripts/device-smoke.sh` (create, executable), `README.md` (a "## Device check"
section).

**Out of scope**: app code; any change to device settings; installing anything on the phone other
than the app APK and one generated test song.

## Git workflow

- Branch: `advisor/014-device-smoke-script`; one commit.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Skeleton with safe preconditions

Create `scripts/device-smoke.sh` (bash, `set -euo pipefail`), options `--apk <path>` (default the
debug APK) and `--keep` (don't clean up). Preconditions, each failing with a clear message:
exactly one `adb` device; model is `A059` (warn but continue otherwise — lights checks become
`PREVIEW`); `ffmpeg` present. Helpers: `dump`, `has_text <text>`, `tap_text <text>` (with retries),
`check <name> <command>` (prints `PASS`/`FAIL name`), `warn <msg>`. Use a temp dir from `mktemp -d`
and `trap` to remove it.

**Verify**: `bash -n scripts/device-smoke.sh` → no output; `shellcheck` (if present) clean.

### Step 2: The checks, in order

1. Install the APK (`adb install -r`), launch, `has_text Glyp`.
2. Generate `glyp-smoke.mp3` (10 s beat, e.g. `ffmpeg -f lavfi -i "aevalsrc=0.6*sin(2*PI*60*t)*exp(-12*mod(t\,0.5)):s=44100" -t 10 …`),
   `adb push` it to `/sdcard/Music/GlypSmoke/`, trigger a media scan
   (`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Music/GlypSmoke/glyp-smoke.mp3`).
3. Import: `New from song` → `Auto compose` → picker `Search` → type `glyp-smoke` → Enter → tap
   `glyp-smoke.mp3`; wait up to 30 s for `Compose` and `Pulse` to be visible.
4. Glyph status within 10 s is one of `● LIVE`, `GLYPHS OFF`, `SCREEN UP` (FAIL on `CONNECTING`
   or `NO GLYPH ACCESS`). Read the two Nothing settings; `warn` if the Glyphs are off or screen-up.
5. Play 2 s, Pause: Glyph frames counted from logcat > 0.
6. Home (`input keyevent 3`), return (`am start …`): status recovers within 5 s, and logcat since
   step 6 shows **no** new `bindService … client com.mauray.glyp` line; Play 1 s → frames > 0.
7. Export as `glyp-smoke`: tap `Export ringtone` → `Export`; wait up to 30 s for `Saved`; the file
   exists in `/sdcard/Ringtones/Glyp/`; if `ffprobe` exists, the COMPOSER tag matches.
8. No `FATAL EXCEPTION` for the app in logcat during the run.
9. Cleanup unless `--keep`: tap `Done`, go Back, delete the `glyp-smoke` project (long-press it,
   tap `Delete selected`, wait 12 s for the Undo to expire), remove `/sdcard/Music/GlypSmoke/` and
   `/sdcard/Ringtones/Glyp/glyp-smoke.ogg`.

Print a summary and exit non-zero if any check failed; last line `ALL CHECKS PASSED` on success.

**Verify**: with the phone attached and unlocked, `scripts/device-smoke.sh` → `ALL CHECKS PASSED`,
in under 3 minutes. Run it twice to prove cleanup works (the second run must not find a leftover
project or ringtone).

### Step 3: README

Add "## Device check": what it needs (unlocked Phone (3a) over USB, `adb`, `ffmpeg`), the command,
what it checks, and that it never changes phone settings.

**Verify**: `grep -n "device-smoke.sh" README.md` → ≥1 match.

## Test plan

The script is the test. Demonstrate: one passing run, one run with `--keep` followed by a normal run
(still passes), and — if the operator agrees to toggle it themselves — one run with the Glyphs tile
off showing a `warn` but no `FAIL`.

## Done criteria

- [ ] `scripts/device-smoke.sh` exists, is executable, passes `bash -n` (and `shellcheck` if available)
- [ ] A run on the phone ends with `ALL CHECKS PASSED`; a second run also passes (cleanup worked)
- [ ] `git status --short` lists only the script and README
- [ ] `plans/README.md` status row updated

## STOP conditions

- The phone is locked or asks for a PIN — never enter credentials; ask the operator to unlock.
- A check would require changing a device setting (Glyph debug mode, Glyphs tile, stay-awake) —
  report instead.
- The picker's UI differs (no `Search` content-desc) — report the dump rather than tapping
  coordinates.

## Maintenance notes

- The script matches UI text; renaming a label (e.g. `Export ringtone`) must update the script in
  the same change. Reviewers: grep the script for any label you rename.
- Plans 003, 009, 010, 011, 012, 013 should run this script before they're marked DONE when a phone
  is available.
