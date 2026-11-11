# Plan 007: Failed writes and failed imports leave nothing behind on disk

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- app/src/main/java/com/mauray/glyp/model/ProjectStore.kt app/src/test/java/com/mauray/glyp/model/ProjectStoreTest.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

`ProjectStore` keeps each Glyp project in `files/projects/<uuid>/` (a copy of the song plus
`project.json` and `lights.bin`). Every file is written to `<name>.tmp` and atomically renamed —
good — but when a write throws, the `.tmp` file stays forever; and when an import fails while
copying the song (unreadable file, storage full, cancelled), the half-created `<uuid>/` folder with
a partial audio copy stays forever too. `list()` silently skips folders without a readable
`project.json`, so the user never sees them and can't delete them — they just eat storage (songs
are several MB each, up to 10 minutes long).

## Current state

`app/src/main/java/com/mauray/glyp/model/ProjectStore.kt` at `f1c86f3`:

```kotlin
// 45
suspend fun list(): List<ProjectMeta> = withContext(io) {
    root.listFiles().orEmpty()
        .mapNotNull { dir -> runCatching { readMeta(dir) }.getOrNull() }
        .sortedByDescending { it.updatedAt }
}
// 56
suspend fun create(fileName: String, durationMs: Long, autoCompose: Boolean, openAudio: () -> InputStream): Project = withContext(io) {
    val id = UUID.randomUUID().toString()
    val dir = File(root, id).apply { mkdirs() }
    val audioFile = File(dir, "audio." + fileName.substringAfterLast('.', "audio"))
    writeAtomically(audioFile) { out -> openAudio().use { it.copyTo(out) } }
    val time = now()
    val meta = ProjectMeta(id, fileName.substringBeforeLast('.'), audioFile.name, durationMs, time, time, autoCompose)
    val lights = LightTrack(Timebase.framesFor(durationMs))
    Project(save(meta, lights), audioFile, lights)
}
// 105
private fun writeAtomically(file: File, write: (OutputStream) -> Unit) {
    file.parentFile?.mkdirs()
    val tmp = File(file.parentFile, file.name + ".tmp")
    tmp.outputStream().use { out ->
        write(out)
        out.flush()
        out.fd.sync()
    }
    Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
```

Existing tests in `app/src/test/java/com/mauray/glyp/model/ProjectStoreTest.kt` (keep them all
passing), notably:

```kotlin
@Test
fun `a failed write keeps the previous version`() = runTest {
    val s = store()
    val p = s.create("a.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }
    // A directory where the temp file should go makes the next write fail before the rename.
    File(tmp.root, "${p.meta.id}/lights.bin.tmp").mkdirs()
    ...
}
```

and `list is newest first and skips broken folders`. The store is built in tests as
`ProjectStore(tmp.root, Dispatchers.Unconfined, now = { clock })`.

**Concurrency constraint**: `create()` runs on the IO dispatcher while the home screen may call
`list()`. A folder without `project.json` might be an import *in progress*. Any sweep in `list()`
must therefore only remove folders that are clearly abandoned — older than an hour.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Store tests | `./gradlew :app:testDebugUnitTest --tests '*ProjectStoreTest*'` | `BUILD SUCCESSFUL` |
| All unit tests | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |

## Scope

**In scope**:
- `app/src/main/java/com/mauray/glyp/model/ProjectStore.kt`
- `app/src/test/java/com/mauray/glyp/model/ProjectStoreTest.kt`

**Out of scope**:
- Changing the file layout or names.
- `HomeViewModel` / UI.

## Git workflow

- Branch: `advisor/007-clean-up-failed-writes`; one commit (code + tests).
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: `writeAtomically` deletes its temp file on failure

Wrap the write and the move: on any exception, `tmp.delete()` (ignore its result — when the "tmp"
is the directory the existing test creates, deleting an empty directory is fine) and rethrow.

**Verify**: `./gradlew :app:testDebugUnitTest --tests '*ProjectStoreTest*'` → `BUILD SUCCESSFUL`.

### Step 2: `create` removes its folder if the import fails

Inside `create`, wrap everything after `mkdirs()` in `try { … } catch (e: Exception) { dir.deleteRecursively(); throw e }`.
Rethrow `CancellationException` too (it's an `Exception`, so the same catch handles it — the
folder is removed and cancellation propagates).

**Verify**: same command → `BUILD SUCCESSFUL`.

### Step 3: `list` sweeps folders abandoned for over an hour

Add a private `sweepAbandoned()` called at the start of `list()`: for each child directory of
`root` with no `project.json` whose `lastModified()` is older than `now() - ABANDONED_MS`
(`ABANDONED_MS = 60 * 60_000L`, in the companion), `deleteRecursively()`. Also delete stray
`*.tmp` files older than that inside valid project folders. Comment *why* the hour exists (an
import may be in progress).

Note: `now()` is the injectable clock; `lastModified()` is the real filesystem time. In tests set
times explicitly with `File.setLastModified(...)` relative to the injected clock.

**Verify**: same command → `BUILD SUCCESSFUL`.

### Step 4: Tests

Add to `ProjectStoreTest.kt`:

1. A write that fails (use the existing directory trick) leaves no `lights.bin.tmp` *file*, and the
   previous `lights.bin` is intact.
2. `create` whose `openAudio` throws `IOException` leaves `tmp.root` with no new folder.
3. `create` whose `openAudio` stream throws mid-copy (an `InputStream` that throws on the second
   `read`) leaves no new folder.
4. A folder without `project.json`, `lastModified` 2 hours before the clock → removed by `list()`.
5. A folder without `project.json`, `lastModified` 1 minute before the clock → kept by `list()`
   (an import in progress).
6. A valid project is never removed by the sweep, whatever its age.

**Verify**: `./gradlew :app:testDebugUnitTest --tests '*ProjectStoreTest*'` → `BUILD SUCCESSFUL`
with the new tests counted; then `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL`.

## Test plan

Six new cases above in `ProjectStoreTest.kt`, matching its style (`store()`, `clock`, `tmp` rule,
backtick names).

## Done criteria

- [ ] `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL`
- [ ] `ProjectStoreTest` contains the 6 new tests, all passing
- [ ] `git status --short` lists only the two in-scope files
- [ ] `plans/README.md` status row updated

## STOP conditions

- The existing test `a failed write keeps the previous version` fails after step 1.
- You find another caller that relies on a project folder existing *before* `project.json` is
  written for longer than seconds (e.g. a background import) — the one-hour rule would then need
  revisiting.

## Maintenance notes

- If imports ever become resumable or very slow (e.g. network sources), raise `ABANDONED_MS` or
  mark in-progress folders explicitly (a `.importing` marker file) instead of relying on age.
