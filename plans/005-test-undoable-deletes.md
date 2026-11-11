# Plan 005: Undoable project deletes are covered by unit tests

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- app/src/main/java/com/mauray/glyp/ui/home/ app/src/test/java/com/mauray/glyp/ui/home/`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: tests
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

On Glyp's home screen ("Compositions"), deleting projects — by swiping a row left, or selecting
several and tapping Delete — is undoable: the projects disappear at once, an "Undo" snackbar
shows, and the files are only removed from disk when the snackbar goes away without Undo, when
the user leaves the screen, or when the ViewModel is cleared. This is the only code in the app
that permanently deletes user work, and it has **no tests**, because it lives in an
`AndroidViewModel` (which needs an `Application`). This plan moves the logic into a plain class
with the same behaviour and tests it against a real `ProjectStore` in a temp folder.

## Current state

- `app/src/main/java/com/mauray/glyp/ui/home/HomeViewModel.kt` lines 27–95 at `f1c86f3`:
  ```kotlin
  private val all = MutableStateFlow<List<ProjectMeta>>(emptyList())

  /** Projects deleted while their Undo is still on screen: hidden, but still on disk. */
  private val hidden = MutableStateFlow(emptySet<String>())

  val projects: StateFlow<List<ProjectMeta>> = combine(all, hidden) { list, gone -> list.filterNot { it.id in gone } }
      .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
  ...
  fun refresh() { viewModelScope.launch { all.value = store.list() } }
  ...
  /** Hides [ids] at once; [commitDelete] deletes them, [undoDelete] brings them back. */
  fun delete(ids: Set<String>) { hidden.update { it + ids } }
  fun undoDelete(ids: Set<String>) { hidden.update { it - ids } }
  fun commitDelete(ids: Set<String>) {
      viewModelScope.launch {
          ids.forEach { store.delete(it) }
          all.value = store.list()
          hidden.update { it - ids }
      }
  }
  fun rename(id: String, name: String) {
      viewModelScope.launch { store.rename(id, name); refresh() }
  }
  // Leaving with an Undo still showing means the user didn't take it back.
  override fun onCleared() {
      val ids = hidden.value
      if (ids.isNotEmpty()) runBlocking { ids.forEach { store.delete(it) } }
  }
  ```
  Note the order inside `commitDelete`: the list is reloaded **before** the ids are un-hidden, so a
  deleted project never flashes back. Keep that.
- Callers: `ui/home/HomeScreen.kt` lines ~123 and ~133 call `vm.delete(ids)`,
  `vm.undoDelete(ids)` and `vm.commitDelete(ids)`; elsewhere it uses `vm.projects`, `vm.refresh()`,
  `vm.rename(...)`. These calls must keep working unchanged.
- `model/ProjectStore.kt` — `suspend fun list()`, `delete(id)`, `rename(id, name)`, and
  `create(fileName, durationMs, autoCompose) { inputStream }`; constructor
  `ProjectStore(root: File, io: CoroutineContext = Dispatchers.IO, now: () -> Long = System::currentTimeMillis)`.
- Test pattern to copy: `app/src/test/java/com/mauray/glyp/model/ProjectStoreTest.kt` builds
  `ProjectStore(tmp.root, Dispatchers.Unconfined, now = { clock })` with a JUnit `TemporaryFolder`
  rule and uses `runTest`. `ui/studio/StudioSessionTest.kt` shows a class under test running on
  `backgroundScope` with `testScheduler.runCurrent()`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Unit tests | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |
| New tests only | `./gradlew :app:testDebugUnitTest --tests '*ProjectListTest*'` | `BUILD SUCCESSFUL` |
| Screenshots | `./gradlew :app:verifyPaparazziDebug` | `BUILD SUCCESSFUL` |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |

## Scope

**In scope**:
- `app/src/main/java/com/mauray/glyp/ui/home/ProjectList.kt` (create)
- `app/src/main/java/com/mauray/glyp/ui/home/HomeViewModel.kt` (delegate to it)
- `app/src/test/java/com/mauray/glyp/ui/home/ProjectListTest.kt` (create)

**Out of scope**:
- `HomeScreen.kt` (the snackbar / Undo UI) — the ViewModel's public methods stay the same.
- `ProjectStore.kt` — behaviour of the store is already tested.
- Import (`create(...)` in the ViewModel) — stays in the ViewModel.

## Git workflow

- Branch: `advisor/005-test-undoable-deletes`
- Two commits: extract `ProjectList` (no behaviour change); add tests.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Extract `ProjectList`

Create `ui/home/ProjectList.kt`:

```kotlin
/**
 * The Compositions list and its undoable deletes: deleted projects are hidden at once and only
 * removed from disk by [commit] (or [commitPending] when the screen goes away for good).
 */
class ProjectList(private val store: ProjectStore, private val scope: CoroutineScope) {
    val projects: StateFlow<List<ProjectMeta>>
    fun refresh()
    fun hide(ids: Set<String>)
    fun undo(ids: Set<String>)
    fun commit(ids: Set<String>)
    fun rename(id: String, name: String)
    /** Deletes whatever is still hidden; blocks, for `onCleared`. */
    fun commitPending()
}
```

Move the bodies from `HomeViewModel` verbatim (`viewModelScope` → `scope`), keeping the comments.
Then make `HomeViewModel` create `private val list = ProjectList(store, viewModelScope)` and
forward: `projects = list.projects`, `refresh()`, `delete → hide`, `undoDelete → undo`,
`commitDelete → commit`, `rename`, and `onCleared() { list.commitPending() }`.

**Verify**: `./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug assembleDebug` → `BUILD SUCCESSFUL`.

### Step 2: Test it

`ProjectListTest.kt`, using a temp-folder store (as in `ProjectStoreTest`) and
`ProjectList(store, backgroundScope)` inside `runTest`, calling `testScheduler.runCurrent()` (or
`advanceUntilIdle()`) after actions. Create projects with
`store.create("a.ogg", 1_000, autoCompose = false) { "x".byteInputStream() }`.

1. `hide` removes the project from `projects` but its folder still exists on disk.
2. `undo` after `hide` brings it back; the folder was never touched.
3. `commit` removes the folder and the project stays out of the list.
4. Hiding two then committing one: the other stays hidden and on disk.
5. `refresh()` while a project is hidden keeps it hidden.
6. `commitPending()` deletes every still-hidden project from disk.
7. `rename` updates the name in `projects` and on disk (`store.load(id).meta.name`).
8. While `commit` runs, the deleted project never reappears: collect `projects` into a list during
   the commit and assert no emission after the `hide` contains the deleted id.

**Verify**: `./gradlew :app:testDebugUnitTest --tests '*ProjectListTest*'` → `BUILD SUCCESSFUL`, 8 tests.

## Test plan

The 8 cases above. Structural patterns: `ProjectStoreTest.kt` (temp folder, clock) and
`StudioSessionTest.kt` (class on `backgroundScope`, `runCurrent()`).

## Done criteria

- [ ] `./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug assembleDebug` → `BUILD SUCCESSFUL`
- [ ] `ProjectListTest` has 8 passing tests
- [ ] `grep -n "hidden" app/src/main/java/com/mauray/glyp/ui/home/HomeViewModel.kt` → no matches (the logic moved)
- [ ] `HomeScreen.kt` unchanged
- [ ] `plans/README.md` status row updated

## STOP conditions

- Any test in step 2 fails against the *moved* code — that is a real bug in today's behaviour;
  report it with the failing case instead of changing the logic.
- `HomeScreen.kt` would need changes.

## Maintenance notes

- The undo window is driven by the snackbar in `HomeScreen.delete()`; if that changes (e.g. a
  longer window or multiple stacked undos), add a case here first.
- Process death while an Undo shows leaves the project on disk (not deleted) — the safe failure.
  Don't "fix" that by deleting eagerly.
