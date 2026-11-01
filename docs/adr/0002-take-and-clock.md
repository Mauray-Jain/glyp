# 2. Takes run on one clock seam and one render tick

Status: accepted

## Context

Playback, jamming and recording each had their own loop in `StudioViewModel`: a playback loop
polling `MediaPlayer` every 8 ms, a separate jam loop on `System.nanoTime()` while paused, and
recording code spread across both. Pad hits were timed on the song clock while playing and on the
wall clock while paused, so pressing play cleared every held pad and pausing did the same. A take
was "committed" by whichever of four paths noticed first, `PadPerformance.isIdle` pruned hits as
a side effect of being asked, and nothing stopped playback when the app went to the background.

## Decision

A **take** module (`studio/TakeRecorder.kt`, interface `studio/Clock.kt`) owns everything that happens while sound or pads are live.

- **Clock seam.** `Clock` supplies `songMs`, `playing`, monotonic `nowNanos`, and
  `play(fromMs)`/`pause`/`setOnEnd`/`release`. `MediaPlayerClock` is the production adapter;
  `FakeClock` (on the coroutine test scheduler's virtual time) drives the tests.
- **One time base for pads.** Hits are stamped on the monotonic clock, so they survive play and
  pause. While recording, each tick measures the offset between monotonic time and the song
  position and maps pad time onto song frames with it.
- **One render tick.** A single loop runs while the song plays or any hit is still lit. Each tick
  moves the playhead, writes the take, mixes the track at the playhead with the pad levels and
  pushes the result to the screen and the Glyph lights.
- **Take bookkeeping.** A take is one light-document transaction. It writes every tick and
  publishes a new revision every few frames (so the timeline follows) and committed on stop, pause, end of song,
  export and leaving the Studio. Leaving or backgrounding the app pauses playback, which commits.
- `PadPerformance.isIdle` is a pure query; finished hits are pruned when a new hit is added.

## Alternatives considered

- **Keep two time bases and translate held hits on play/pause.** Fewer moving parts per tick, but
  every transition needs its own conversion; the offset approach does one translation in one place.
- **Choreographer / `withFrameNanos` for the tick.** Ties the tick to the UI frame clock, which
  stops when the screen is off or the activity is stopped; a coroutine `delay` loop keeps the
  Glyphs moving under any UI state and is trivially driven by a test dispatcher.
- **Write the take into a separate layer and merge at commit.** The timeline would need to draw two
  sources while recording. Flushing into the open transaction keeps one source of truth.
