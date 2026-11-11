# Plan 001: Gradle always runs on JDK 17, whatever `JAVA_HOME` points at

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- settings.gradle.kts gradle/ gradle.properties README.md`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P1
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: dx
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

This is an Android app (Kotlin, Jetpack Compose) built with Gradle. The build only works on
JDK 17, but nothing makes Gradle *itself* run on 17: the Kotlin toolchain setting only applies to
compiling. On a machine whose default Java is newer (it happened on the maintainer's Arch box with
OpenJDK 27), `./gradlew` dies immediately with an unreadable error:

```
* What went wrong:
27.0.1.0.3
```

Gradle 8.8+ supports **Daemon JVM criteria** (`gradle/gradle-daemon-jvm.properties`): the
build declares which JDK the Gradle daemon must run on, and Gradle finds (or downloads) it. That
removes the "export JAVA_HOME=… first" ritual for every human, agent and CI job.

## Current state

- `gradle/wrapper/gradle-wrapper.properties:3`:
  `distributionUrl=https\://services.gradle.org/distributions/gradle-8.14.3-bin.zip`
- `settings.gradle.kts` (whole file):
  ```kotlin
  pluginManagement {
      repositories {
          google()
          mavenCentral()
          gradlePluginPortal()
      }
  }
  dependencyResolutionManagement {
      repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
      repositories {
          google()
          mavenCentral()
      }
  }

  rootProject.name = "Glyp"
  include(":app")
  ```
- `app/build.gradle.kts:38-40`: `kotlin { jvmToolchain(17) }` — compile toolchain only.
- There is **no** `gradle/gradle-daemon-jvm.properties` today.
- `README.md` "## Build" says: "Needs JDK 17 and an Android SDK with platform 37 and build-tools
  37.0.0 (point `local.properties` at it with `sdk.dir=...`)."
- The Glyph SDK `.aar` is not in git; `app/build.gradle.kts` fails fast with
  "Glyph SDK missing: run ./scripts/fetch-glyph-sdk.sh first." if `app/libs/glyph-matrix-sdk-2.0.aar`
  is absent.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Fetch Glyph SDK (once) | `./scripts/fetch-glyph-sdk.sh` | `Saved …/app/libs/glyph-matrix-sdk-2.0.aar` |
| Unit tests | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |
| Daemon JVM in use | `./gradlew -q help --task updateDaemonJvm` | task help printed (proves the task exists) |
| Which JDK the daemon runs on | `./gradlew --status` | lists a daemon; the JVM path is JDK 17 |

Before step 1 the commands above only work with `JAVA_HOME` pointing at a JDK 17 (e.g.
`export JAVA_HOME=/usr/lib/jvm/java-17-openjdk` on Arch; find yours with
`ls /usr/lib/jvm` or `/usr/libexec/java_home -v 17` on macOS).

## Scope

**In scope**:
- `gradle/gradle-daemon-jvm.properties` (create, via the Gradle task — do not hand-write it)
- `settings.gradle.kts` (add the toolchain resolver plugin, if step 2 needs it)
- `README.md` (the "## Build" section only)

**Out of scope**:
- `gradle/wrapper/*` — upgrading Gradle itself belongs to plan 010.
- `app/build.gradle.kts` — `jvmToolchain(17)` stays as is.
- `gradle.properties` — do not set `org.gradle.java.home`; it is machine-specific.

## Git workflow

- Branch: `advisor/001-pin-gradle-daemon-jdk`
- Commit message style (from `git log`): an imperative summary line, a blank line, then a short
  wrapped paragraph explaining why. Example subject: `Bind to the Glyph service once per process; only the session follows visibility`.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Generate the daemon JVM criteria

With `JAVA_HOME` on a JDK 17, run:

```bash
./gradlew updateDaemonJvm --jvm-version=17
```

This writes `gradle/gradle-daemon-jvm.properties` containing `toolchainVersion=17` (Gradle 8.14
may also write vendor or download-URL keys; keep whatever it writes).

**Verify**: `cat gradle/gradle-daemon-jvm.properties` → contains a line `toolchainVersion=17`.

### Step 2: Let Gradle find or download a JDK 17

Gradle can only *download* a missing toolchain if a resolver is configured. In
`settings.gradle.kts`, add this `plugins {}` block directly **after** the closing brace of
`pluginManagement {}` (Gradle requires `pluginManagement` to stay first):

```kotlin
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
```

Then re-run step 1's command so the properties file can record download URLs.

**Verify**: `./gradlew -q help` → exit 0. If the plugin version cannot be resolved, try `0.10.0`;
if that also fails, see STOP conditions.

### Step 3: Prove it works from a non-17 `JAVA_HOME`

Stop running daemons, point `JAVA_HOME` at a **different** JDK the machine has (21 or newer if
available; list with `ls /usr/lib/jvm`), and build:

```bash
./gradlew --stop
JAVA_HOME=<other JDK> ./gradlew :app:testDebugUnitTest
./gradlew --status
```

**Verify**: the test task ends `BUILD SUCCESSFUL`, and `--status` shows the daemon's JVM is a
JDK 17.

### Step 4: Update the README

Replace the first sentence of "## Build" with:

> Needs an Android SDK with platform 37 and build-tools 37.0.0 (point `local.properties` at it with
> `sdk.dir=...`). Gradle runs itself on JDK 17 (see `gradle/gradle-daemon-jvm.properties`) and
> downloads one if none is installed; the `java` on your `PATH` only needs to be able to start
> the Gradle wrapper.

**Verify**: `grep -n "gradle-daemon-jvm.properties" README.md` → one match.

## Test plan

No code changes, so no new unit tests. The verification is step 3: the full unit-test suite
(currently 92 tests) passes when started from a non-17 `JAVA_HOME`. Count them with:
`grep -h -o 'tests="[0-9]*"' app/build/test-results/testDebugUnitTest/*.xml | grep -o '[0-9]*' | paste -sd+ | bc`
→ 92 (or more, if other plans landed first).

## Done criteria

- [ ] `gradle/gradle-daemon-jvm.properties` exists and contains `toolchainVersion=17`
- [ ] `./gradlew :app:testDebugUnitTest` → `BUILD SUCCESSFUL` when `JAVA_HOME` is a JDK other than 17
- [ ] `./gradlew assembleDebug` → `BUILD SUCCESSFUL`
- [ ] `git status --short` lists only `gradle/gradle-daemon-jvm.properties`, `settings.gradle.kts`, `README.md`
- [ ] `plans/README.md` status row updated

## STOP conditions

- `./gradlew` still crashes *before* reaching the daemon when `JAVA_HOME` is very new (e.g. the
  same bare-version error as above). That means the Gradle 8.14.3 **launcher** cannot run on that
  JDK at all; report which JDK versions fail. Fixing that needs a newer Gradle wrapper (plan 010) —
  do not upgrade the wrapper here.
- The Foojay resolver cannot be resolved at either version.
- Any unit test fails that passed before your change.

## Maintenance notes

- When the project moves to a newer JDK (e.g. as part of plan 010), regenerate the file with
  `./gradlew updateDaemonJvm --jvm-version=<N>` and keep `jvmToolchain(N)` in
  `app/build.gradle.kts` in step.
- CI (plan 002) benefits directly: the workflow no longer has to pick the right JDK by hand.
