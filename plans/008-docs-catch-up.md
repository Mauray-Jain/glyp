# Plan 008: README and ADRs describe the app as it is today

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- README.md docs/adr/ GLOSSARY.md`
> If any in-scope file changed since this plan was written (plan 001 and 002 may edit README.md —
> that is expected; keep their changes), compare the "Current state" excerpts against the live
> text before proceeding; on an unexplained mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none (run after 001/002 if they are in flight, to avoid README conflicts)
- **Category**: docs
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

Glyp's docs were written before a long day of fixes and features, and now say things that are
wrong or missing. The README tells people Glyph debug mode is *required* (on the current Nothing OS
build the lights work without it), says nothing about the 10-minute song limit or the new status
labels that explain why the lights are dark, and ADR 0005 still describes a memory layout that was
replaced to fix a crash. Wrong docs cost more than missing docs: the next person (or agent) acts on
them. A `CLAUDE.md` for agents already exists at the repo root (written separately); this plan only
has to keep the README and ADRs consistent with it.

## Current state

Facts to document (verified on a Nothing Phone (3a), model A059, Nov 2026 — inline, don't
re-derive):

1. **Glyph debug mode**: with `nt_glyph_interface_debug_enable` set to 0, the Glyph service still
   accepted the app's frames and the lights worked. The manifest still uses the debug key
   (`<meta-data android:name="NothingKey" android:value="test" />`, `AndroidManifest.xml:18`), which
   Nothing documents as working while debug mode is on. So: debug mode "may be needed, depending on
   the Nothing OS version".
2. **Songs up to 10 minutes** (`HomeViewModel.MAX_SONG_MS`); longer files are refused at import
   with "Pick a song up to 10 minutes long."
3. **Glyph status labels** in the Studio header (`StudioScreen.kt`, `glyphStatus`):
   `● LIVE` (connected), `CONNECTING`, `GLYPHS OFF` (Nothing OS has the Glyph Interface off —
   Quick Settings tile or its bedtime schedule — a 2-second hint explains, tap the label to see it
   again), `SCREEN UP` (the phone lies screen-up; Nothing OS keeps the Glyphs dark; tap for the
   reason), `NO GLYPH ACCESS` (the Glyph service refused registration), `PREVIEW` (not a
   Phone (3a), so no real lights).
4. **Home screen**: swipe a composition left to delete; long-press to select several (Select all,
   Rename, Delete); every delete shows an Undo snackbar.
5. **Export**: exporting the same name again replaces that ringtone in place (it stays the default
   if it was); export can be cancelled while encoding.
6. **Memory design (replaces part of ADR 0005)**: `PcmAudio` carries its own `frameCount`, so the
   decode buffer is never copied to trim it; analysis runs on a mono copy decimated toward 22 kHz
   in one pass (`AudioAnalyzer.decimationFor`, `PcmAudio.toMono(decimation)`) instead of a
   full-rate float copy; export resamples a frame at a time (`Resampled` in `OpusTranscoder.kt`)
   instead of building a 48 kHz copy. A 10-minute song opens at ~185 MB of the 256 MB heap.
   An `OutOfMemoryError` while opening or exporting becomes the message "This song is too long for
   this phone's memory." instead of a crash.
7. **Glyph connection lifecycle (new decision, not yet in any ADR)**: `GlyphController` binds to
   Nothing's Glyph service once per process (first `enterForeground`) and keeps the binding;
   `MainActivity.onStart/onStop` only open/close a *session*. Going to the background sends an
   all-zero frame, `turnOff()`, `closeSession()`. A watchdog re-binds while visible and not
   registered, after 2 s, 4 s, 8 s, then every 15 s (the SDK never re-binds on its own; a lost bind
   used to leave the app stuck on CONNECTING). While visible it watches two `Settings.Global` keys:
   `led_effect_enable` (0 = Glyph Interface off) and `glyph_screen_upward_state` (1 = lying
   screen-up), and keeps sending frames in both states so the lights return instantly.
   Alternatives considered: bind/unbind with visibility (original; lost binds left it stuck); keep
   a session open in the background (Nothing's SDK only serves foreground apps).

Current text to replace:
- `README.md` "## Run on the phone":
  > Third-party apps can only drive the Glyph lights while Glyph debug mode is on (it switches off
  > after 48 hours):
- `README.md` intro bullets (top of file) describe Auto compose / Edit / Record / Export but not the
  home-screen management or the limits.
- `docs/adr/0005-song.md` "## Alternatives considered" says:
  > **Keep only the mono analysis copy and re-decode for export.** Saves memory during editing
  > (about 10 MB per minute of stereo audio) at the cost of a second decode on every export.
  > Decoding once was the requested behaviour; revisit if long songs cause memory pressure.
- `docs/adr/README.md` — a table of ADRs 0001–0006 (format: `| [0006](0006-light-shapes.md) | One trail and one level curve for every light shape |`).
- ADR file format (copy it): `# N. Title`, `Status: accepted`, `## Context`, `## Decision`,
  `## Consequences` (optional), `## Alternatives considered`.

## Commands you will need

| Purpose | Command | Expected |
|---|---|---|
| Find the stale claim | `grep -n "only drive the Glyph lights while Glyph debug mode is on" README.md` | 1 match before, 0 after |
| Check links | `grep -o "](docs/adr/[^)]*)" README.md; ls docs/adr` | every linked file exists |

No build needed (docs only), but `./gradlew :app:testDebugUnitTest` must still pass (sanity).

## Scope

**In scope**: `README.md`, `docs/adr/0004-ringtone-export.md`, `docs/adr/0005-song.md`,
`docs/adr/0007-glyph-connection.md` (create), `docs/adr/README.md`, `GLOSSARY.md` (only to add the
status-label terms if it has a matching section).

**Out of scope**: `CLAUDE.md` (exists; if it contradicts something here, report it rather than
editing), any code, any other doc.

## Git workflow

- Branch: `advisor/008-docs-catch-up`; one commit.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: README

- Replace the debug-mode paragraph in "## Run on the phone" with: debug mode *may* be needed
  depending on the Nothing OS version (fact 1), keep the `adb shell settings put …` command, and
  add one sentence that the Studio header shows `GLYPHS OFF` / `SCREEN UP` when Nothing OS is the
  reason the lights are dark (fact 3).
- In the intro bullets: add a **Compositions** bullet (fact 4), append "up to 10 minutes" to Auto
  compose (fact 2), and add replace-in-place + cancel to Export (fact 5).
- Add a short "## Glyph status" section with the six labels from fact 3 as a table.

**Verify**: the "Find the stale claim" grep → 0 matches; `grep -c "SCREEN UP" README.md` → ≥1.

### Step 2: ADR 0005 update

Append a section `## Update: long songs (2026-11)` to `docs/adr/0005-song.md` summarising fact 6,
and edit the "Keep only the mono analysis copy…" alternative's last sentence to say memory pressure
did occur (10-minute songs crashed) and was solved without re-decoding (point to the update).

**Verify**: `grep -n "Update: long songs" docs/adr/0005-song.md` → 1 match.

### Step 3: ADR 0004 update

Append `## Update: re-export and cancel (2026-11)`: same display name in `Ringtones/Glyp` →
overwrite in place via MediaStore (URI kept, stays default); a file owned by an earlier install
falls back to a new entry; encoding checks for cancellation on each progress report and saves
nothing when cancelled.

**Verify**: `grep -n "Update: re-export" docs/adr/0004-ringtone-export.md` → 1 match.

### Step 4: New ADR 0007

Create `docs/adr/0007-glyph-connection.md` from fact 7 in the ADR format above, and add a row
`| [0007](0007-glyph-connection.md) | One Glyph binding per process; only the session follows visibility |`
to `docs/adr/README.md`.

**Verify**: `ls docs/adr/0007-glyph-connection.md` exists; `grep -c 0007 docs/adr/README.md` → 1.

## Test plan

Docs only. Sanity: `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL`. Read the README top to
bottom once and check every statement against the facts list.

## Done criteria

- [ ] Stale debug-mode claim gone; `SCREEN UP`, `GLYPHS OFF`, `10 minutes` all appear in README.md
- [ ] ADR 0004 and 0005 have their update sections; ADR 0007 exists and is indexed
- [ ] `git status --short` lists only in-scope files
- [ ] `plans/README.md` status row updated

## STOP conditions

- The code no longer matches a fact above (e.g. `MAX_SONG_MS` changed, status labels renamed) —
  document what the code says *now* and note the difference in your report.
- `CLAUDE.md` contradicts the README after your edits.

## Maintenance notes

- Plans 003, 004, 007, 011, 012, 013 change behaviour described here; each should touch the README
  or an ADR when it lands. Reviewers: check that.
