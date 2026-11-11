# Plan 012: Picking a Glyph ringtone imports its lights, so it can be edited

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- app/src/main/java/com/mauray/glyp/export/OggOpus.kt app/src/main/java/com/mauray/glyp/export/GlyphRingtone.kt app/src/main/java/com/mauray/glyp/model/ProjectStore.kt app/src/main/java/com/mauray/glyp/ui/home/HomeViewModel.kt app/src/test/java/com/mauray/glyp/export/ app/src/test/java/com/mauray/glyp/model/`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M
- **Risk**: LOW–MED (new read path; no change to existing exports)
- **Depends on**: none (plan 007 also edits `ProjectStore.create`; land one, then rebase the other)
- **Category**: direction
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

A "Glyph ringtone" is an Ogg Opus file whose Vorbis-comment tags carry light data that Nothing OS
plays on the back of the phone. Glyp can **write** them (export) but not **read** them: picking one
of your own exported ringtones, or one made in Nothing's Glyph Composer, imports only the audio and
throws the lights away. All the hard parts already exist — Ogg page parsing (`OggOpus`), and the tag
format with its encoding (`GlyphRingtone`) — so reading them back is mostly the inverse of code that
is already written and tested.

## Current state

The tag format (`export/GlyphRingtone.kt`, KDoc at the top of the file, quoted):

```
 * Tag format as documented by the community tools (SebiAi/custom-nothing-glyph-tools):
 *  - AUTHOR:   light data, one CSV line per 60 fps frame, each value followed by ',', lines
 *              ending in CRLF; zlib (best compression), base64 without '=' padding, 76-char lines.
 *  - CUSTOM1:  timeline dots for Nothing's Glyph Composer UI ("ms-id," pairs), same encoding.
 *  - COMPOSER: "v1-Asteroids Glyph Composer" for the Phone (3a) / (3a) Pro.
 *  - CUSTOM2:  zone count, "36cols".
```

Encoding today (`GlyphRingtone.kt`):

```kotlin
fun csv(lights: LightFrames, frames: Int): ByteArray  // "v,v,…,v,\r\n" per frame, 36 values
fun encode(data: ByteArray): String {                    // zlib BEST_COMPRESSION, Base64 w/o padding, 76-char lines + "\n"
```

`export/OggOpus.kt`: `fun retag(input, tags)`, `fun isOggOpus(bytes)`, and private
`parsePages(bytes): List<Page>` where `Page(headerType, granule, serial, sequence, lacing: IntArray,
body: ByteArray)` and `Page.lastPacketContinues` (last lacing value is 255). In an Ogg Opus stream
page 0 holds `OpusHead`; the `OpusTags` packet starts on page 1 and may span pages (it ends on the
first page whose `lastPacketContinues` is false); audio starts on the next page. The `OpusTags`
packet layout (little-endian): `"OpusTags"`, `u32 vendorLength`, vendor bytes, `u32 count`, then
`count` × (`u32 length`, UTF-8 `KEY=value`). Writer: private `buildOpusTags` in the same file.

Import today (`ui/home/HomeViewModel.kt` `create(audio: Uri, autoCompose, onCreated)`): reads the
duration with `MediaMetadataRetriever`, checks it's 1 ms–10 min, then
`store.create(fileName, duration, autoCompose) { resolver.openInputStream(audio) }`.
`model/ProjectStore.kt` `create(...)` copies the audio and writes an **empty** `LightTrack(Timebase.framesFor(durationMs))`;
with `autoCompose = true` the Studio composes lights on first open (`ProjectMeta.pendingAutoCompose`).

Test helpers to reuse: `app/src/test/java/com/mauray/glyp/export/OggTestFiles.kt` —
`sampleOpus()` builds a small valid Ogg Opus stream, `opusTags(comments)`, `readComments(packet)`,
`parse/write/packets`. Existing tests: `OggOpusTest` (incl. tags over 64 KB spanning pages),
`GlyphRingtoneTest` (csv golden format, tags, build).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Unit tests | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |
| Export tests | `./gradlew :app:testDebugUnitTest --tests '*OggOpusTest*' --tests '*GlyphRingtoneTest*'` | `BUILD SUCCESSFUL` |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |

## Scope

**In scope**: `export/OggOpus.kt` (add `readTags`), `export/GlyphRingtone.kt` (add the decoder),
`model/ProjectStore.kt` (`create` accepts initial lights), `ui/home/HomeViewModel.kt` (detect and
use), and tests under `app/src/test/java/com/mauray/glyp/{export,model}/`.

**Out of scope**: export code paths; UI layout (no new buttons — a picked Glyph ringtone simply
arrives with its lights); support for other Nothing phones' layouts (5 or 15 columns) — refuse them.

## Git workflow

- Branch: `advisor/012-import-glyph-ringtones`; commits: reader+decoder with tests; store; view model.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: `OggOpus.readTags(bytes): Map<String, String>`

Parse pages, concatenate the bodies of page 1 through the page that ends the `OpusTags` packet,
check the `"OpusTags"` magic, read vendor and comments, and return comments split at the first
`=`, keys upper-cased. Return an empty map for a non-Opus file (don't throw for "not ours").

Tests (`OggOpusTest`): `readTags(retag(sampleOpus, tags))` returns those tags; a tag packet over
64 KB (reuse the existing big-tag case) reads back intact; `readTags` of random bytes → empty map.

**Verify**: export tests command → `BUILD SUCCESSFUL`.

### Step 2: `GlyphRingtone.decodeLights(tags): LightTrack?`

Inverse of `encode` + `csv`: take `tags["AUTHOR"]`, strip whitespace/newlines, Base64-decode
(Java's `Base64.getDecoder()` accepts missing `=` padding), inflate with `java.util.zip.Inflater`,
split lines on `\n` (drop `\r`), split values on `,` ignoring empty tokens, parse ints clamped to
`0..GlyphZones.MAX_BRIGHTNESS`. Return `null` unless every non-empty line has exactly
`GlyphZones.COUNT` (36) values (other Nothing phones use other counts) — also return `null` when
`AUTHOR` is missing or undecodable.

Tests (`GlyphRingtoneTest`): round trip — `decodeLights(tags(title, lights, durationMs).toMap())`
equals `lights` frame by frame; a 15-column CSV → `null`; garbage AUTHOR → `null`; no AUTHOR → `null`.

**Verify**: export tests command → `BUILD SUCCESSFUL`.

### Step 3: `ProjectStore.create(..., lights: LightTrack? = null)`

When `lights` is given, write it (padded with zeros or trimmed to `Timebase.framesFor(durationMs)`)
instead of the empty track, and set `pendingAutoCompose = false`. Test in `ProjectStoreTest`:
created project loads with those lights and `pendingAutoCompose == false`.

**Verify**: `./gradlew :app:testDebugUnitTest --tests '*ProjectStoreTest*'` → `BUILD SUCCESSFUL`.

### Step 4: Import uses the lights

In `HomeViewModel.create`, before `store.create`: open the input stream, read the first 64 bytes; if
`OggOpus.isOggOpus(head)`, read the whole file (it's ≤ 10 minutes of Opus, a few MB), call
`GlyphRingtone.decodeLights(OggOpus.readTags(bytes))`, and pass the result as `lights` (and pass the
bytes as the audio source instead of reopening the URI). Non-Glyph files behave exactly as today.

**Verify**: `./gradlew :app:testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`.

### Step 5 (device, if available)

Export a project from Glyp, then import that `.ogg` from `Ringtones/Glyp` as a new song: the new
project's timeline shows the same lights (no auto-compose). Also try a file made by Nothing's own
Glyph Composer on the phone, if one exists (`adb shell ls /sdcard/Ringtones/`). Report what happened.

## Test plan

Listed per step: `OggOpusTest` (+3), `GlyphRingtoneTest` (+4), `ProjectStoreTest` (+1). Use
`OggTestFiles.sampleOpus()` for audio; don't add binary fixtures.

## Done criteria

- [ ] `./gradlew :app:testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`, 8+ new tests
- [ ] Importing a non-Glyph song behaves exactly as before (existing tests pass)
- [ ] Device check done or reported as pending
- [ ] README "Auto compose"/import text mentions that Glyph ringtones keep their lights
- [ ] `plans/README.md` status row updated

## STOP conditions

- A real Nothing Glyph Composer file decodes to the wrong frame count or column count — report one
  decoded line and the `COMPOSER`/`CUSTOM2` tags instead of guessing the format.
- Reading the whole file in step 4 is needed for very large inputs (> 50 MB) — stop; streaming the
  tag parse would be a different design.

## Maintenance notes

- `decodeLights` must stay the exact inverse of `csv` + `encode`; if the export format changes,
  change both and keep the round-trip test.
- Only the 36-column (Phone (3a)) layout is supported; supporting other phones means a zone map per
  device (`GlyphZones` is (3a)-specific).
