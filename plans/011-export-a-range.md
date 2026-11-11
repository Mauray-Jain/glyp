# Plan 011: A ringtone can be exported from just part of the song

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- app/src/main/java/com/mauray/glyp/export/ app/src/main/java/com/mauray/glyp/model/LightTrack.kt app/src/main/java/com/mauray/glyp/ui/studio/StudioSession.kt app/src/main/java/com/mauray/glyp/ui/studio/StudioScreen.kt app/src/test/java/com/mauray/glyp/`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: M–L
- **Risk**: MED (touches the export pipeline end to end)
- **Depends on**: plans/004-safe-ringtone-replace.md (soft; both touch `export/`)
- **Category**: direction
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

Glyp turns a song into a "Glyph ringtone" for the Nothing Phone (3a): an Ogg Opus file whose tags
carry 60-frames-per-second light data that Nothing OS plays on the phone's back while it rings.
Today the **whole song** is exported — up to 10 minutes — although a ringtone only ever plays its
first seconds before the call is answered. Users want the chorus, not the intro. The Studio already
lets you drag out a time range on the timeline (Edit and Compose use it), so the missing piece is
"export only this range".

Product decisions taken here (the operator may override — see STOP conditions):
- The export dialog offers **Whole song** and, when a range is selected, **Selection (m:ss–m:ss)**.
  Default: **Whole song** (today's behaviour; no surprise).
- A selection shorter than 1 second can't be exported (button disabled with a hint).
- Exporting a selection always re-encodes the audio (an Ogg Opus original can't be cut losslessly
  here), even when the source is already Opus.

## Current state

- Frame ↔ time: `audio/Song.kt` `object Timebase` — `msAt(frame)` (first whole ms of a frame),
  `frameAt(ms)`, `framesFor(durationMs)`; 60 frames per second (`GlyphZones.FPS`).
- `ui/studio/StudioControls.kt`: `var selection: IntRange?` — the timeline range in **light frames**
  (set by dragging; cleared with the Range card's ✕ in Edit).
- `ui/studio/StudioSession.kt:293-317` (`f1c86f3`):
  ```kotlin
  suspend fun export(title: String): Result<Ringtone> {
      val o = editable() ?: return Result.failure(IllegalStateException("Busy"))
      o.recorder.pause()
      o.meta = o.meta.copy(name = title)
      val job = ExportJob(title, o.audioFile, o.song.pcm, o.document.snapshot(), o.meta.durationMs)
      setExporting(o, Phase.Exporting(0f))
      ...
          val ringtone = exportRingtone(job) { progress.value = it }
  ```
- `export/RingtoneExporter.kt`:
  ```kotlin
  class ExportJob(val title: String, val audioFile: File, val pcm: PcmAudio, val lights: LightTrack, val durationMs: Long)
  ...
  suspend fun export(job: ExportJob, onProgress: (Float) -> Unit = {}): Ringtone = withContext(Dispatchers.IO) {
      val context = coroutineContext
      val audio = opusAudio(job) { context.ensureActive(); onProgress(it) }
      val ringtone = GlyphRingtone.build(job.title, job.lights, job.durationMs, audio)
      ensureActive()
      installer.install(ringtone, job.title).also { onProgress(1f) }
  }
  /** The original file when it already is Ogg Opus, otherwise the song transcoded. */
  private fun opusAudio(job: ExportJob, onProgress: (Float) -> Unit): ByteArray {
      val original = job.audioFile.readBytes()
      if (OggOpus.isOggOpus(original)) return original
      ... OpusTranscoder.encode(job.pcm, opus) { onProgress(it * 0.9f) } ...
  }
  ```
- `export/OpusTranscoder.kt`: `fun encode(pcm: PcmAudio, output: File, onProgress: (Float) -> Unit = {})`
  reads frames through `internal class Resampled(pcm: PcmAudio, channels: Int, rate: Int)` which
  exposes `frameCount` and `operator fun get(frame, channel)`; it reads PCM frames `0 until
  pcm.frameCount`. `Resampled` is tested in `app/src/test/java/com/mauray/glyp/export/ResampledTest.kt`.
- `export/GlyphRingtone.kt`: `build(title, lights: LightFrames, durationMs, opus)`; `tags()` trims
  lights to `Timebase.framesFor(durationMs)` frames. Tested by `GlyphRingtoneTest.kt`.
- `model/LightTrack.kt`: `class LightTrack(frameCount: Int, data: ShortArray = …)` with
  `operator get(frame, zone)`, `set`, `copy()`, `writeTo/readFrom`.
- The export dialog: `ui/studio/StudioScreen.kt`, `private fun ExportDialog(stage, phase, name,
  onExport: (title: String) -> Unit, onSetRingtone, onCancelExport, onDismiss)`; the form shows an
  `OutlinedTextField` for the name. Choice chips elsewhere use `TogglePill(text, selected, onClick)`
  from `ui/components/Nothing.kt` (see the Layer/Replace pills in `ui/studio/RecordPanel.kt`).
  Times are formatted with `formatTime(frame)` in `StudioScreen.kt`.
- `StudioSessionTest.kt` has a fake `exportRingtone = { job, onProgress -> exports += job; … }`
  that records every `ExportJob` — use it to assert what the session passes.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Unit tests | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |
| Screenshots | `./gradlew :app:verifyPaparazziDebug` | `BUILD SUCCESSFUL` (no export-dialog screenshot exists) |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |
| Inspect an exported file | `ffprobe -v error -show_entries format=duration -of csv=p=0 <file.ogg>` | the selection's length in seconds (±0.05) |

## Scope

**In scope**: `model/LightTrack.kt`, `export/RingtoneExporter.kt`, `export/OpusTranscoder.kt`,
`ui/studio/StudioSession.kt`, `ui/studio/StudioScreen.kt` (export dialog + wiring), and tests in
`app/src/test/java/com/mauray/glyp/{model,export,ui/studio}/`.

**Out of scope**: the timeline/selection gestures, `GlyphRingtone`'s tag format, `OggOpus`,
`RingtoneInstaller` (naming/overwrite rules unchanged).

## Git workflow

- Branch: `advisor/011-export-a-range`; commits per step group (model+export, session, UI).
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: `LightTrack.slice(range)`

Add `fun slice(frames: IntRange): LightTrack` — a new track of `frames.count()` frames copying those
rows (clamped to the track). Test in `LightTrackTest.kt` (create; pattern: `LightDocumentTest.kt`):
values copied, length right, out-of-range clamped.

**Verify**: `./gradlew :app:testDebugUnitTest --tests '*LightTrackTest*'` → `BUILD SUCCESSFUL`.

### Step 2: Transcode a window of the PCM

Give `Resampled` an optional window: `Resampled(pcm, channels, rate, from: Int = 0, until: Int =
pcm.frameCount)` (PCM frames); `frameCount` and `get` work relative to `from`. Give
`OpusTranscoder.encode` an optional `pcmFrames: IntRange? = null` passed to `Resampled`. Extend
`ResampledTest` with: a window passes the right samples through; resampling a window doesn't read
outside it.

**Verify**: `./gradlew :app:testDebugUnitTest --tests '*ResampledTest*'` → `BUILD SUCCESSFUL`.

### Step 3: `ExportJob.range`

Add `val range: IntRange? = null` (light frames) to `ExportJob`. In `RingtoneExporter.export`:
when `range != null`, use `job.lights.slice(range)`, duration
`Timebase.msAt(range.last + 1) - Timebase.msAt(range.first)`, and always transcode with
`pcmFrames = pcmFrom..<pcmUntil` where `pcmFrom = (Timebase.msAt(range.first) * pcm.sampleRate / 1000).toInt()`
(likewise for the end, clamped to `pcm.frameCount`). With `range == null`, behaviour is unchanged.

**Verify**: `./gradlew :app:testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`.

### Step 4: Session

`StudioSession.export(title: String, range: IntRange? = null)` passes `range` into the `ExportJob`;
reject a range shorter than 60 frames with `Result.failure(IllegalArgumentException("Pick at least
a second of the song."))`. Tests in `StudioSessionTest`: the recorded job has the range; a short
range fails with that message; `null` behaves as before (existing tests keep passing).

**Verify**: `./gradlew :app:testDebugUnitTest --tests '*StudioSessionTest*'` → `BUILD SUCCESSFUL`.

### Step 5: Dialog

In `ExportDialog`'s form, when the Studio has a selection, show two `TogglePill`s under the name
field — "Whole song" (selected by default) and "Selection m:ss–m:ss" (use `formatTime`). Pass the
choice through `onExport(title, range)` to `session.export(title, range)`. Without a selection, the
dialog looks exactly as today.

**Verify**: `./gradlew :app:verifyPaparazziDebug assembleDebug` → `BUILD SUCCESSFUL`.

### Step 6 (device, if available)

Open a project, drag a ~10 s range, Export → Selection. Pull the file
(`adb pull /sdcard/Ringtones/Glyp/<name>.ogg`) and check with `ffprobe` that its duration is the
selection's length (±0.05 s), and that `ffprobe -show_entries stream_tags=COMPOSER` still shows
`v1-Asteroids Glyph Composer`.

## Test plan

New: `LightTrackTest` (slice), `ResampledTest` (window cases), `StudioSessionTest` (range passed;
short range rejected). All existing tests unchanged and passing.

## Done criteria

- [ ] `./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug assembleDebug` → `BUILD SUCCESSFUL`
- [ ] New tests listed above exist and pass
- [ ] Export without a selection produces the same output as before (existing export tests pass)
- [ ] Step 6 done on a device, or reported as pending
- [ ] `plans/README.md` status row updated; README "Export" bullet mentions selections

## STOP conditions

- The operator wants a different default (e.g. "Selection" pre-selected) or a fixed maximum
  ringtone length — ask before building the UI.
- Exported ringtones of a selection don't light the Glyphs in sync when they ring (test by setting
  one as the ringtone and calling the phone) — report; the light CSV and audio offsets disagree.

## Maintenance notes

- The light CSV starts at the selection's first frame and the audio at the same instant; any future
  fade-in/out of the cut audio must not shift that alignment.
- Plan 012 (import) reads ringtones back; a cut ringtone imports as a short project — that's fine.
