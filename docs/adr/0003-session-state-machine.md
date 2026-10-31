# 3. A session state machine decides what the Studio may do

Status: accepted

## Context

`StudioUiState` mixed three nullable "modes" (`loading: String?`, `exportProgress: Float?`,
`exportedUri: Uri?`) with booleans (`playing`, `recording`) whose combinations were never
checked: you could undo, auto-compose, apply a tool or start an export while a take was writing
into the same array. `StudioActions` had twenty methods, half of them setters for plain controls
(tool, brightness, pulse rate, composer settings, pad brightness, overdub, selection, zones) that
only the UI reads. `ExportDialog` took the concrete `StudioViewModel` to reach the exporter.

## Decision

A **session** module (`ui/studio/StudioSession.kt`) owns the Studio's lifecycle as one sealed
type, `StudioState` = `Loading(label, progress)` | `Failed(message)` | `Open(phase, …)`, where an
open project's `Phase` is `Idle`, `Playing`, `Recording` or `Exporting(progress)`:

```
Loading -> Idle <-> Playing <-> Recording
           Idle/Playing -> Exporting -> Idle
Loading -> Failed
```

- The session is a plain class on a `CoroutineScope`; the ViewModel only builds it and closes it.
  Its tests run on a test dispatcher with a fake clock and a temp-dir store.
- Every action checks the phase, read from the take recorder rather than from the published
  state (which a collector updates a moment later). Edit, undo/redo, compose, export and mode
  switching are rejected while recording or exporting; the UI greys out the same controls from
  the same rule (`Phase.canEdit`).
- Plain settings are UI state (`StudioControls`). Actions take what they need as arguments
  (`paint(PaintRequest)`, `compose(params, range)`, `pressPad(pad, level)`, `toggleRecord(overdub)`), so
  `StudioActions` shrinks to the commands the session must arbitrate.
- Export progress and its result belong to the dialog: `export(title)` reports progress through
  the `Exporting` phase and returns `Result<Ringtone>`; the dialog keeps `ExportStage` (closed /
  form / encoding / saved) locally and needs only lambdas, not the ViewModel.
- One-off messages are a `Flow<String>` rather than a nullable field that the UI has to clear.

## Alternatives considered

- **Keep the ViewModel as the state machine.** It would stay untestable without Robolectric
  (AndroidViewModel, MediaPlayer). Moving the logic to a plain class costs one small factory.
- **Separate flags with guard helpers.** Smaller diff, but illegal states stay representable.
- **Make every control session state** (one source of truth for everything). Means a round trip
  through the session for every slider move and keeps the interface wide; nothing outside the UI
  reads those values.
