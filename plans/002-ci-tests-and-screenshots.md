# Plan 002: Every push and PR runs the unit tests, screenshot checks, lint and a debug build

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- .github/ scripts/fetch-glyph-sdk.sh app/build.gradle.kts`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: plans/001-pin-gradle-daemon-jdk.md (soft: CI also sets up JDK 17 itself)
- **Category**: dx
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

Glyp (an Android app for the Nothing Phone (3a)) has 92 JVM unit tests and Paparazzi screenshot
tests, but nothing runs them automatically: the repo has no `.github/` directory. Every change in
the history was verified by someone remembering to run Gradle by hand; one regression a day slipping
through is likely. A single GitHub Actions workflow makes "is main green?" a fact instead of a
hope, and is the safety net every other plan in this directory relies on.

## Current state

- No `.github/` directory exists.
- Remote: `git@github.com:Mauray-Jain/glyp.git` (GitHub).
- `scripts/fetch-glyph-sdk.sh` downloads Nothing's Glyph SDK (not redistributable, so not in git):
  ```bash
  SDK=glyph-matrix-sdk-2.0.aar
  URL="https://raw.githubusercontent.com/Nothing-Developer-Programme/Glyph-Developer-Kit/main/sdk/$SDK"
  DEST="$(cd "$(dirname "$0")/.." && pwd)/app/libs/$SDK"
  mkdir -p "$(dirname "$DEST")"
  curl -fL --progress-bar -o "$DEST" "$URL"
  ```
- `app/build.gradle.kts`: `compileSdk = 37`, `buildToolsVersion = "37.0.0"`, `kotlin { jvmToolchain(17) }`,
  and `check(glyphSdk.exists()) { "Glyph SDK missing: run ./scripts/fetch-glyph-sdk.sh first." }`.
- Screenshot goldens live in `app/src/test/snapshots/images/*.png`; `verifyPaparazziDebug`
  compares against them.
- `local.properties` (git-ignored) normally holds `sdk.dir`; on CI the `ANDROID_HOME` env var is
  used instead.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fetch Glyph SDK | `./scripts/fetch-glyph-sdk.sh` | `Saved …/app/libs/glyph-matrix-sdk-2.0.aar` |
| Unit tests | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |
| Screenshot check | `./gradlew :app:verifyPaparazziDebug` | `BUILD SUCCESSFUL` |
| Lint | `./gradlew :app:lintDebug` | `BUILD SUCCESSFUL`; `app/build/reports/lint-results-debug.txt` ends `0 errors, N warnings` |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |
| YAML parses | `python3 -c "import yaml,sys;yaml.safe_load(open('.github/workflows/ci.yml'))"` | no output, exit 0 |
| Workflow lint (if installed) | `actionlint .github/workflows/ci.yml` | no output, exit 0 |

(Locally these need a JDK 17 unless plan 001 has landed.)

## Scope

**In scope**:
- `.github/workflows/ci.yml` (create)
- `README.md` (add a CI badge line under the title — optional)

**Out of scope**:
- Any Gradle file — the build must work on CI exactly as it does locally.
- Release builds / signing (plan 009).
- Committing the Glyph SDK `.aar` — its licence does not allow redistribution.

## Git workflow

- Branch: `advisor/002-ci`
- Commit message: imperative summary + short "why" paragraph (see `git log`).
- Do NOT push or open a PR unless the operator instructed it. The workflow can only be proven
  green after a push, which is the operator's call.

## Steps

### Step 1: Write the workflow

Create `.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
    branches: [main]
  pull_request:

concurrency:
  group: ci-${{ github.ref }}
  cancel-in-progress: true

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - uses: android-actions/setup-android@v3

      - name: Android platform 37 and build-tools 37.0.0
        run: sdkmanager "platforms;android-37" "build-tools;37.0.0"

      - uses: gradle/actions/setup-gradle@v4

      - name: Fetch Nothing's Glyph SDK
        run: ./scripts/fetch-glyph-sdk.sh

      - name: Unit tests, screenshot check, lint, debug build
        run: ./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug :app:lintDebug assembleDebug

      - name: Upload reports on failure
        if: failure()
        uses: actions/upload-artifact@v4
        with:
          name: reports
          path: |
            app/build/reports/
            app/build/test-results/
            app/build/paparazzi/
```

**Verify**: the YAML-parses command from the table → exit 0; `actionlint` (if available) → no output.

### Step 2: Run the exact CI command locally

```bash
./scripts/fetch-glyph-sdk.sh   # skip if app/libs/glyph-matrix-sdk-2.0.aar exists
./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug :app:lintDebug assembleDebug
```

**Verify**: `BUILD SUCCESSFUL`. This is the same command CI runs; if it fails locally it will fail
on CI.

### Step 3 (optional): README badge

Directly under `# Glyp` in `README.md` add:

```markdown
[![CI](https://github.com/Mauray-Jain/glyp/actions/workflows/ci.yml/badge.svg)](https://github.com/Mauray-Jain/glyp/actions/workflows/ci.yml)
```

**Verify**: `grep -c "actions/workflows/ci.yml/badge.svg" README.md` → 1.

## Test plan

No new tests — this plan runs the existing ones. Proof is step 2 locally and, after the operator
pushes, a green "CI" run in the repo's Actions tab.

## Done criteria

- [ ] `.github/workflows/ci.yml` exists and parses as YAML
- [ ] `./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug :app:lintDebug assembleDebug` → `BUILD SUCCESSFUL` locally
- [ ] `git status --short` lists only `.github/workflows/ci.yml` (and `README.md` if step 3 was done)
- [ ] `plans/README.md` status row updated

## STOP conditions

- `verifyPaparazziDebug` fails locally on a clean checkout — the goldens are out of date; report
  which images differ instead of re-recording them (re-recording would hide a real regression).
- The Glyph SDK URL in `scripts/fetch-glyph-sdk.sh` returns 404.
- `sdkmanager` on CI can't find `platforms;android-37` (report it; the operator decides whether to
  pin a different image or platform).

## Maintenance notes

- Screenshot goldens differ slightly between machines when fonts or rendering change; if CI fails
  only on `verifyPaparazziDebug` after a dependency bump, re-record with
  `./gradlew :app:recordPaparazziDebug` *in the same PR that caused it*, and review the diff.
- When plan 010 bumps platform or build-tools versions, update the `sdkmanager` line here too.
