# Plan 004: Re-exporting a ringtone never leaves a broken file behind

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- app/src/main/java/com/mauray/glyp/export/ app/src/test/java/com/mauray/glyp/export/`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

Glyp exports "Glyph ringtones" (Ogg Opus files with light data in their tags) to the phone's
`Ringtones/Glyp` folder through Android's MediaStore. Exporting the same name again overwrites the
existing entry in place, on purpose: the MediaStore URI stays the same, so if that ringtone is the
phone's **default ringtone** it stays the default with the new lights.

The overwrite opens the file in truncate mode (`"wt"`) and then, in a `finally`, marks the entry
ready again. If the write fails part-way (storage full, I/O error), the `finally` still publishes a
truncated or empty file — and if it was the default ringtone, the phone's ringtone is now broken.
The fix: remember the old bytes before overwriting and put them back if the write fails.

## Current state

- `app/src/main/java/com/mauray/glyp/export/RingtoneInstaller.kt` — the only MediaStore code.
  `install()` (lines 35–68) finds an existing own entry and calls `replace()`; otherwise inserts a
  new pending entry (and deletes it if writing fails — that path is already safe).
- The unsafe part, lines 80–96 at `f1c86f3`:
  ```kotlin
  private fun replace(uri: Uri, file: ByteArray, title: String) {
      val resolver = appContext.contentResolver
      resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 1) }, null, null)
      try {
          resolver.openOutputStream(uri, "wt")!!.use { it.write(file) }
      } finally {
          resolver.update(
              uri,
              ContentValues().apply {
                  put(MediaStore.Audio.Media.IS_PENDING, 0)
                  put(MediaStore.Audio.Media.TITLE, title)
              },
              null,
              null,
          )
      }
  }
  ```
- Ringtone files are a few MB (a 10-minute song exports to ~13 MB), so holding the old bytes in
  memory during the write is fine — export already holds the whole new file as a `ByteArray`.
- `RingtoneInstaller` is Android-only (MediaStore), so it can't be unit-tested directly. Pure
  export logic lives in testable objects next to it — e.g. `export/GlyphRingtone.kt` (tested by
  `app/src/test/java/com/mauray/glyp/export/GlyphRingtoneTest.kt`) and the internal class
  `Resampled` in `export/OpusTranscoder.kt` (tested by `ResampledTest.kt`). Follow that pattern:
  put the rollback rule in a small pure function and test it.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Unit tests | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |
| New tests only | `./gradlew :app:testDebugUnitTest --tests '*OverwriteTest*'` | `BUILD SUCCESSFUL` |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |

(Needs JDK 17 unless plan 001 landed; `./scripts/fetch-glyph-sdk.sh` once.)

## Scope

**In scope**:
- `app/src/main/java/com/mauray/glyp/export/RingtoneInstaller.kt`
- `app/src/main/java/com/mauray/glyp/export/Overwrite.kt` (create)
- `app/src/test/java/com/mauray/glyp/export/OverwriteTest.kt` (create)

**Out of scope**:
- The new-entry path in `install()` (lines 48–67) — already deletes on failure.
- `RingtoneExporter.kt`, `GlyphRingtone.kt`, `OggOpus.kt`.
- Changing *when* a replace happens (same display name in `Ringtones/Glyp`).

## Git workflow

- Branch: `advisor/004-safe-ringtone-replace`
- One commit; imperative summary + "why" paragraph as in `git log`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Add the pure rollback helper

Create `export/Overwrite.kt`:

```kotlin
package com.mauray.glyp.export

/**
 * Writes [new] over a file whose current content [readOld] returns, through [write] (which
 * truncates). If writing fails, the old content is written back before the error is rethrown,
 * so a failed overwrite never leaves a truncated file behind.
 */
internal fun overwriteWithRollback(readOld: () -> ByteArray, write: (ByteArray) -> Unit, new: ByteArray) {
    val old = readOld()
    try {
        write(new)
    } catch (e: Exception) {
        try {
            write(old)
        } catch (restore: Exception) {
            e.addSuppressed(restore)
        }
        throw e
    }
}
```

**Verify**: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.

### Step 2: Use it in `replace()`

Rewrite `replace()` so that:
1. it still sets `IS_PENDING = 1` first;
2. it calls `overwriteWithRollback(readOld = { resolver.openInputStream(uri)!!.use { it.readBytes() } }, write = { bytes -> resolver.openOutputStream(uri, "wt")!!.use { it.write(bytes) } }, new = file)`;
3. in a `finally`, it sets `IS_PENDING = 0` (the entry must never stay pending — a pending entry is
   invisible to the system ringtone picker);
4. it updates `TITLE` to the new title **only after** the write succeeded (on failure the old file,
   and so the old title, is back).

Keep the existing `SecurityException` catch in `install()` working: `readOld` throwing
`SecurityException` (an entry from an earlier install of the app) must still fall through to
"add a new one". Since `readOld()` runs before any write, nothing is modified in that case.

**Verify**: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`.

### Step 3: Test the helper

Create `OverwriteTest.kt` (JUnit 4, pattern: `GlyphRingtoneTest.kt`). Simulate the file with a
`var content: ByteArray` and a `write` lambda that can be told to fail after truncating:

1. Success: content becomes `new`.
2. Write fails after truncating (lambda sets `content = ByteArray(0)` then throws `IOException`):
   the `IOException` propagates, and `content` equals the **old** bytes.
3. Write fails and the restore write also fails: the original `IOException` propagates with the
   restore failure in `suppressed`.
4. `readOld` throws `SecurityException`: it propagates and `write` was never called.

**Verify**: `./gradlew :app:testDebugUnitTest --tests '*OverwriteTest*'` → `BUILD SUCCESSFUL`, 4 tests.

## Test plan

The four `OverwriteTest` cases above; plus the full suite still passes
(`./gradlew :app:testDebugUnitTest`). Optional manual check on a phone: export a project, export it
again under the same name — `adb shell ls /sdcard/Ringtones/Glyp/` still shows one file of that name.

## Done criteria

- [ ] `./gradlew :app:testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`
- [ ] `OverwriteTest` has 4 passing tests
- [ ] `grep -n '"wt"' app/src/main/java/com/mauray/glyp/export/RingtoneInstaller.kt` → exactly one match, inside the `write` lambda passed to `overwriteWithRollback`
- [ ] `git status --short` lists only in-scope files
- [ ] `plans/README.md` status row updated

## STOP conditions

- `ContentResolver.openInputStream` on the existing entry fails for reasons other than
  `SecurityException` on a real device (report it — reading back our own MediaStore entry is
  expected to work).
- The replace path turns out to need changes in `install()` beyond calling the new `replace()`.

## Maintenance notes

- If ringtones ever stream to disk instead of being built in memory (ADR 0004 chose in-memory),
  the rollback must switch to a temp copy instead of a `ByteArray`.
- Plan 013 (notification/alarm sounds) reuses `install()`; it gets this safety for free.
