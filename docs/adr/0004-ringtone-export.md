# 4. Ringtone export is a pure module plus one install adapter

Status: accepted

## Context

`RingtoneExporter` decoded, transcoded, built tags, re-paged the Ogg stream, wrote to MediaStore
and set the default ringtone, all in one Android-bound class. The interesting part (Nothing's tag
format and the Ogg re-paging with CRCs) had no tests, and the "Modify system settings" flow was
split between the exporter, the ViewModel and the dialog.

## Decision

- **`export/GlyphRingtone`** is pure JVM: `build(title, snapshot, durationMs, opus)` takes a light
  snapshot and the Ogg Opus bytes and returns the tagged file. It owns the CSV, the Composer
  timeline dots, the zlib/base64 encoding and the Ogg re-paging (`OggOpus`). It is tested on the
  JVM: retagging round-trips with valid CRCs and renumbered pages, a tag packet over 64 KB spans
  pages correctly, and the CSV matches a golden string.
- **`export/RingtoneInstaller`** is the only Android adapter: it publishes the file to
  `Ringtones/Glyp` through MediaStore and owns the WRITE_SETTINGS flow (set now, or send
  the user to the permission screen and say so).
- **`RingtoneExporter`** just sequences: snapshot on the caller's thread, get Opus audio (copy or
  transcode the song's PCM), build, install.

## Alternatives considered

- **Write tags with MediaMuxer metadata.** Not supported for Ogg on Android; re-paging stays.
- **Stream pages instead of building in memory.** Ringtone files are a few MB; in-memory keeps the
  code and the tests simple.
- **Ask for WRITE_SETTINGS up front.** Most users export without setting a default ringtone;
  asking only when they tap "Set as ringtone" keeps the first export permission-free.

## Update: re-export and cancel (2026-11)

- Exporting a name that already exists in `Ringtones/Glyp` overwrites that MediaStore entry in
  place: the URI is kept, so a ringtone that is the phone's default stays the default with the new
  lights. The old file is read first and written back if the overwrite fails. A file owned by an
  earlier install of the app can't be written, so that case falls back to a new entry.
- Encoding checks for cancellation on each progress report; a cancelled export saves nothing.
