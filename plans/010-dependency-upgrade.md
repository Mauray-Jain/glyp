# Plan 010: Dependencies are current, upgraded in small verified stages

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- gradle/ app/build.gradle.kts build.gradle.kts settings.gradle.kts`
> Plans 001 and 009 may have edited some of these; that's expected. Compare the "Current state"
> versions against `gradle/libs.versions.toml`; if a version here is already newer, skip it.

## Status

- **Priority**: P3
- **Effort**: M (stages A–B); stage C is a separate, larger spike
- **Risk**: MED (Compose / Kotlin bumps can change behaviour and screenshot pixels)
- **Depends on**: plans/002-ci-tests-and-screenshots.md (CI catches regressions),
  plans/003-test-glyph-controller.md and plans/005-test-undoable-deletes.md (more coverage first)
- **Category**: migration
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

Glyp's toolchain is about a year behind: Compose BOM `2025.09.00` (newest `2026.09.00`), Kotlin
`2.2.20` (newest `2.4.21`), Gradle `8.14.3`, AGP `8.13.0` (newest 8.x `8.13.2`, newest overall
`9.5.1`), targetSdk 36 (37 available). None of it is broken today, but every month behind makes the
eventual jump bigger, blocks newer Material components, and the old Gradle launcher can't start on
the newest JDKs (see plan 001). Upgrading in stages, each fully verified, keeps any breakage small
and attributable.

## Current state

`gradle/libs.versions.toml` `[versions]` at `f1c86f3`:

```toml
agp = "8.13.0"
kotlin = "2.2.20"
composeBom = "2025.09.00"
activityCompose = "1.10.1"
lifecycle = "2.9.2"
coroutines = "1.10.2"
serialization = "1.9.0"
materialIcons = "1.7.8"
paparazzi = "2.0.0-alpha02"
junit = "4.13.2"
```

`gradle/wrapper/gradle-wrapper.properties`: `gradle-8.14.3-bin.zip`.
`app/build.gradle.kts`: `compileSdk = 37`, `targetSdk = 36`, `minSdk = 34`, `jvmToolchain(17)`.

Targets reported by Android Lint (`./gradlew :app:lintDebug`, report
`app/build/reports/lint-results-debug.txt`, issues `GradleDependency`, `NewerVersionAvailable`,
`AndroidGradlePluginVersion`) on 2026-11-11:

| Dependency | Now | Target |
|---|---|---|
| Gradle wrapper | 8.14.3 | 8.14.7 (stage A) |
| AGP (`agp`) | 8.13.0 | 8.13.2 (stage A); 9.5.1 (stage C, optional) |
| kotlinx-coroutines | 1.10.2 | 1.11.0 |
| kotlinx-serialization-json | 1.9.0 | 1.12.0 |
| activity-compose | 1.10.1 | 1.13.0 |
| lifecycle | 2.9.2 | 2.11.0 |
| Paparazzi | 2.0.0-alpha02 | 2.0.0-alpha05.1 |
| Kotlin (+ compose & serialization plugins) | 2.2.20 | 2.4.21 (stage B) |
| Compose BOM | 2025.09.00 | 2026.09.00 (stage B) |

Known API to watch in stage B: `ui/home/HomeScreen.kt` `ProjectRow` uses
`rememberSwipeToDismissBoxState(confirmValueChange = …)` and `SwipeToDismissBox(state, …,
gesturesEnabled = …)` from material3 1.3; newer material3 deprecates `confirmValueChange` in favour
of an `onDismiss` callback on `SwipeToDismissBox`. Behaviour to preserve: swiping a row left deletes
(with Undo); right swipe does nothing; no swipe while selecting.

Paparazzi goldens: `app/src/test/snapshots/images/*.png` (9 images).

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Full gate | `./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug :app:lintDebug assembleDebug` | `BUILD SUCCESSFUL` |
| Re-record screenshots | `./gradlew :app:recordPaparazziDebug` | `BUILD SUCCESSFUL`; changed PNGs in `git status` |
| Wrapper bump | `./gradlew wrapper --gradle-version 8.14.7` | `BUILD SUCCESSFUL`; properties file updated |
| Device check (if plan 014 landed and a phone is attached) | `scripts/device-smoke.sh` | ends with `ALL CHECKS PASSED` |

## Scope

**In scope**: `gradle/libs.versions.toml`, `gradle/wrapper/*`, `app/build.gradle.kts`,
`build.gradle.kts`, `settings.gradle.kts`, source files only where a version bump forces an API
change (e.g. `HomeScreen.kt` swipe API), screenshot goldens only when the change is reviewed.

**Out of scope**: refactors "while you're there"; Glyph SDK version; minSdk.

## Git workflow

- Branch: `advisor/010-dependency-upgrade`; **one commit per stage** (A, B, then C/D only if done),
  so any stage can be reverted alone.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1 (stage A): Patch and minor bumps

Wrapper → 8.14.7 (command above); `agp` → 8.13.2; coroutines 1.11.0; serialization 1.12.0;
activityCompose 1.13.0; lifecycle 2.11.0; paparazzi 2.0.0-alpha05.1.

**Verify**: full gate → `BUILD SUCCESSFUL`. If only `verifyPaparazziDebug` fails, see step 3.

### Step 2 (stage B): Kotlin 2.4.21 and Compose BOM 2026.09.00

Bump `kotlin` (it drives all three Kotlin plugins) and `composeBom` together (Compose's compiler
plugin is tied to the Kotlin version). Fix compile errors and deprecation *errors* only. For the
swipe-to-delete API: if `confirmValueChange` is deprecated, move the delete into
`SwipeToDismissBox(onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() })` and
keep `enableDismissFromStartToEnd = false` and `gesturesEnabled = !selecting`.

**Verify**: full gate → `BUILD SUCCESSFUL` (screenshots: step 3).

### Step 3: Screenshot differences

If `verifyPaparazziDebug` fails after a stage, run `recordPaparazziDebug`, then list changed images
(`git status --short app/src/test/snapshots`). Open each changed PNG next to the old one
(`git show HEAD:<path> > /tmp/old.png`). Acceptable: anti-aliasing / font hinting / ≤2 px shifts.
Not acceptable: missing elements, different colours, text cut off — STOP.

**Verify**: after re-recording, full gate → `BUILD SUCCESSFUL`; your report lists every re-recorded
image with a one-line reason.

### Step 4 (stage D, separate commit): targetSdk 37

Only after A and B are green. Set `targetSdk = 37`, read the Android 17 behaviour-changes page for
apps targeting 37, and check the app's affected areas (foreground media playback, MediaStore writes,
`WRITE_SETTINGS`, edge-to-edge). Run the full gate and, if a phone is attached, the device smoke
script.

**Verify**: full gate → `BUILD SUCCESSFUL`; lint no longer reports `OldTargetApi`.

### Step 5 (stage C, optional spike): AGP 9.x

Do **not** attempt in the same branch unless stages A, B, D are done and green. AGP 9 changes how
Kotlin is applied (built-in Kotlin support) and requires a newer Gradle major. Treat as a spike:
try it, record what breaks, and report rather than forcing it through.

## Test plan

No new tests. Every stage must pass the full gate; stage B and D should also pass the device smoke
script (plan 014) if a phone is available, since Compose and targetSdk changes can alter runtime
behaviour that unit tests don't see (swipe gestures, dialogs, playback in background).

## Done criteria

- [ ] `gradle/libs.versions.toml` matches the stage A/B targets (or newer)
- [ ] Wrapper is 8.14.7 (or newer 8.x)
- [ ] Full gate `BUILD SUCCESSFUL`
- [ ] Every re-recorded screenshot is listed in the report with a reason
- [ ] One commit per completed stage
- [ ] `plans/README.md` status row updated (note which stages were done)

## STOP conditions

- A stage needs more than ~5 source edits to compile — report the list instead of grinding.
- Swipe-to-delete, Undo, or the Glyph connection behave differently on a device after stage B.
- A screenshot shows a real visual regression (step 3).
- Stage C breaks the Kotlin plugin setup — stop; that's a spike, not part of "done".

## Maintenance notes

- After this lands, regenerate `gradle/gradle-daemon-jvm.properties` (plan 001) if the JDK changes,
  and update the `sdkmanager` line in `.github/workflows/ci.yml` (plan 002) if build-tools change.
- Paparazzi is still an alpha; pin exact versions and expect golden churn on its upgrades.
