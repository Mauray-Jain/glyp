# 1. One light document owns the light track

Status: accepted

## Context

The Studio's `LightTrack` was a public `ShortArray` reached from everywhere: the ViewModel
edited it in place, `AutoComposer` wrote into it on `Dispatchers.Default` while the timeline was
drawing it on the main thread, undo kept up to 30 full copies with no regard for song length,
recording wrote straight into it frame by frame, and `ProjectStore.save` serialised the live array
on the IO dispatcher while the user kept editing. Autosave was a cancel-and-relaunch job in the
ViewModel; `onCleared` saved with `runBlocking` regardless of whether anything changed, and a
crash mid-write left a truncated `lights.bin`.

## Decision

A **light document** module (`model/LightDocument.kt`) is the only owner of a project's frames
while it is open.

- **Interface.** Readers see `LightFrames` (frame count + `get(frame, zone)` + `copyFrame`) or
  take a `snapshot()`. Writers call `edit { }` for a one-shot change, or `begin()` for a
  transaction that is updated several times and committed once (used by takes). Background work
  calls `editInBackground(context) { }`, which computes into a snapshot off the main thread and
  commits it atomically, recomputing if the document changed in the meantime.
- **Confinement.** All calls must come from the thread that created the document (main in the
  app). This is checked, not just documented, so a stray background write fails loudly in tests.
- **Undo/redo** live inside the document and are bounded by a byte budget (32 MB by default)
  instead of a count, so a 10-minute song keeps fewer steps than a 10-second ringtone rather than
  using ten times the memory.
- **Revision and dirty** are tracked by the document; `revision` is a `StateFlow` the UI and the
  autosaver watch.
- **ProjectStore** writes through a temp file and an atomic rename, takes snapshots (never the
  live document), and no longer depends on `Context`, so it is tested against a temp dir.
  Autosave is debounced on `revision`, skips when not dirty, and is flushed when the Studio closes.

`LightTrack` stays a plain mutable value, but its array is private: it is what transactions
mutate and what snapshots are.

## Alternatives considered

- **Immutable persistent track (copy on every edit).** Simplest concurrency story, but a frame-
  by-frame take would copy the whole track 60 times a second. Rejected.
- **Diff-based undo** (store only the changed frame range). Less memory per step, but every tool
  and the composer would have to report what they touched. The byte budget gets most of the
  benefit with none of the bookkeeping; revisit if undo depth on long songs becomes a complaint.
- **Locking instead of confinement.** Would let the composer write directly, but every reader on
  the draw path would need the lock. Confinement plus "compute on a copy" keeps the draw path
  lock-free.
