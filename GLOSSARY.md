# Glossary

Domain terms used in the code, the docs and the UI. When a word here appears in a class name it
means exactly this.

| Term | Meaning |
|---|---|
| **Zone** | One individually dimmable segment of the Glyph lights. The Phone (3a) has 36: C 0–19 (top arc), A 20–30 (right arc), B 31–35 (bottom arc). |
| **Strip** | A group of zones that form one physical light bar (C, A or B; shown as Top, Right, Bottom). |
| **Frame** | One 1/60 s step of light data; every frame holds one stored value per zone. |
| **Stored value** | A zone's brightness as written to a project or ringtone, 0..4095. |
| **Level** | A brightness in 0..1 before it is turned into a stored value by the level curve. |
| **Level curve** | The single mapping from level to stored value, shared by composing, editing and pads. |
| **Trail** | A bright head with a fading tail moving along an ordered list of zones; used by sweeps, the Sweep pad and the Flow style. |
| **Light track** | The light data of a project: frame count × 36 stored values. A plain value; copying it is a snapshot. |
| **Light document** | The one owner of a project's light track while it is open. Edits go through it as transactions; it keeps the revision, the dirty flag and undo/redo. Readers get snapshots or frame views. |
| **Transaction** | One undoable change to the light document: checkpoint, one or more mutations, commit. |
| **Revision** | A counter the light document bumps on every committed (or flushed) mutation, so views know to redraw. |
| **Snapshot** | An independent copy of the light track, safe to hand to another thread (save, export, background compose). |
| **Song** | A project's audio, decoded once: PCM, length in frames, waveform and analysis. Also owns the frame ↔ millisecond time base. |
| **Analysis** | Per-frame audio features of a song (bands, loudness, onset flux) used by the automatic composer. |
| **Style** | One automatic composition algorithm (Pulse, Spectrum, Flow, Classic). |
| **Pad** | One of the five Record-mode buttons; pressing it lights a set of zones. |
| **Hit** | One press of a pad, from finger down to the end of its fade or trail. |
| **Performance** | The set of current hits and the levels they produce over time. |
| **Jam** | Playing the pads while the song is paused: lights only, nothing is written. |
| **Take** | One recording pass: pad hits written into the light document as the song plays, committed as one undoable transaction when it stops. |
| **Take recorder** | The module that owns playback on the clock, the pad performance, the current take and the frame shown on the Glyphs. |
| **Layer / Replace** | How a take combines with existing lights: keep the brighter of the two, or overwrite. |
| **Clock** | The seam that supplies song position and monotonic time to the take (MediaPlayer on the phone, a fake in tests). |
| **Session** | The Studio's state machine for one open project: Loading, Idle, Playing, Recording, Exporting or Failed. It decides which actions are allowed. |
| **Ringtone** | An Ogg Opus file whose Vorbis comments carry the light data in Nothing's format. |
| **Install** | Publishing a ringtone to MediaStore and, with permission, making it the default ringtone. |
