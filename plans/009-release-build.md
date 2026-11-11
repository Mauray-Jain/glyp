# Plan 009: A signed, shrunk release build can be produced without committing any secret

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- app/build.gradle.kts app/proguard-rules.pro app/src/main/AndroidManifest.xml .gitignore`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3 (only matters once the app is distributed beyond the maintainer's phone)
- **Effort**: M
- **Risk**: MED (code shrinking can strip classes the Glyph SDK needs at runtime)
- **Depends on**: plans/014-device-smoke-script.md (soft: the smoke script is the easiest way to
  prove a shrunk build still drives the lights)
- **Category**: dx (release)
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

Glyp has only ever been built as a debug APK. Three things block a real release:

1. **The Nothing API key is the debug one.** `AndroidManifest.xml` hard-codes
   `<meta-data android:name="NothingKey" android:value="test" />`; Nothing's Glyph Developer Kit
   says `test` is for development (it works while Glyph debug mode is on). A distributed app needs
   a key from Nothing's developer programme. That key is a credential and must not be committed.
2. **No signing config**, so `assembleRelease` produces an unsigned APK.
3. **No code shrinking** (`isMinifyEnabled = false`), so the APK ships unused Compose/Material code.

This plan makes the key and signing values come from Gradle properties on the build machine
(never from git), and turns on R8 with the keep rules the Glyph SDK needs.

## Current state

- `app/src/main/AndroidManifest.xml:15-19` at `f1c86f3`:
  ```xml
  <!-- "test" works while Glyph debug mode is enabled:
       adb shell settings put global nt_glyph_interface_debug_enable 1 -->
  <meta-data
      android:name="NothingKey"
      android:value="test" />
  ```
- `app/build.gradle.kts:14-27`:
  ```kotlin
  defaultConfig {
      applicationId = "com.mauray.glyp"
      // The Glyph SDK only runs on Nothing OS with Android 14+.
      minSdk = 34
      targetSdk = 36
      versionCode = 1
      versionName = "0.1.0"
  }

  buildTypes {
      release {
          isMinifyEnabled = false
          proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      }
  }
  ```
- `app/proguard-rules.pro` exists and is empty.
- The Glyph SDK is a local `.aar` (`app/libs/glyph-matrix-sdk-2.0.aar`, fetched by
  `scripts/fetch-glyph-sdk.sh`) with packages `com.nothing.ketchum` (public API: `GlyphManager`,
  `Glyph`, `Common`, `GlyphException`) and `com.nothing.thirdparty` (the `IGlyphService` AIDL
  binder interface it uses to talk to the system service). Binder interfaces are looked up by
  name across processes — shrinking/renaming them breaks the connection.
- Serialization: `model/ProjectStore.kt:18` has `@Serializable data class ProjectMeta(...)`
  (kotlinx.serialization; its library ships consumer R8 rules for generated serializers).
- `.gitignore` already ignores `/local.properties`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Release build | `./gradlew assembleRelease` | `BUILD SUCCESSFUL`; APK under `app/build/outputs/apk/release/` |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |
| Unit tests | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |
| Throwaway keystore (testing only) | `keytool -genkeypair -keystore /tmp/glyp-test.jks -alias test -keyalg RSA -keysize 2048 -validity 1 -storepass testpass -keypass testpass -dname CN=test` | file created |
| Check APK signature | `$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk` | prints `CN=test` signer |
| Install on a phone | `adb install -r app/build/outputs/apk/release/app-release.apk` | `Success` |

## Scope

**In scope**: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
(the meta-data value only), `README.md` (a short "## Release build" section).

**Out of scope**:
- Committing any keystore, password or Nothing key — ever. Do not add them to `gradle.properties`
  in the repo; they go in `~/.gradle/gradle.properties` on the build machine.
- Play Store listing, versioning scheme, CI release job.

## Git workflow

- Branch: `advisor/009-release-build`; commit per step.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Nothing key from a Gradle property

In the manifest change `android:value="test"` to `android:value="${nothingKey}"` and update the
comment above it to say the value comes from the `glyp.nothingKey` Gradle property (default `test`,
the development key). In `defaultConfig` add:

```kotlin
// Nothing's API key for the Glyph SDK: "test" (development) unless the build machine sets
// glyp.nothingKey in ~/.gradle/gradle.properties. Never commit a real key.
manifestPlaceholders["nothingKey"] = providers.gradleProperty("glyp.nothingKey").getOrElse("test")
```

**Verify**: `./gradlew assembleDebug` → `BUILD SUCCESSFUL`; then
`$ANDROID_HOME/build-tools/37.0.0/aapt2 dump xmltree --file AndroidManifest.xml app/build/outputs/apk/debug/app-debug.apk | grep -A2 NothingKey` → shows value `test`.

### Step 2: Signing from Gradle properties

Add a `signingConfigs { create("release") { … } }` that reads `glyp.storeFile`,
`glyp.storePassword`, `glyp.keyAlias`, `glyp.keyPassword` via `providers.gradleProperty(...)`, and
assign it to the release build type **only when `glyp.storeFile` is set** (otherwise leave release
unsigned, so builds without secrets still work).

**Verify**: with the throwaway keystore,
`./gradlew assembleRelease -Pglyp.storeFile=/tmp/glyp-test.jks -Pglyp.storePassword=testpass -Pglyp.keyAlias=test -Pglyp.keyPassword=testpass`
→ `BUILD SUCCESSFUL`; `apksigner verify --print-certs` → signer `CN=test`. Without the `-P` flags,
`./gradlew assembleRelease` still → `BUILD SUCCESSFUL` (unsigned).

### Step 3: Turn on R8 with Glyph SDK keep rules

Set `isMinifyEnabled = true` and `isShrinkResources = true` for release. Put in
`app/proguard-rules.pro`:

```proguard
# Nothing's Glyph SDK talks to the system Glyph service over a binder interface that is matched by
# name, and is used through its public classes; keep both packages whole.
-keep class com.nothing.ketchum.** { *; }
-keep class com.nothing.thirdparty.** { *; }
```

**Verify**: the signed `assembleRelease` from step 2 → `BUILD SUCCESSFUL`; the release APK is
smaller than the debug APK (`ls -l` both).

### Step 4: Prove the shrunk build on a phone

If a Nothing Phone (3a) is connected (`adb devices` shows one), install the signed release APK
(uninstall the debug app first only if `adb install` reports a signature mismatch — that wipes the
app's projects, so ask the operator first), open a project, press Play, and confirm the Studio
header shows `● LIVE` and `adb logcat -d | grep -c "GlyphService.*finalColors"` is > 0 after
playing. If plan 014's `scripts/device-smoke.sh` exists, run it with `--apk <release apk>` instead.

**Verify**: as described; if no phone is available, say so in the report and leave this plan
`IN PROGRESS` with "needs device check".

### Step 5: README

Add "## Release build": the four `glyp.*` signing properties and `glyp.nothingKey`, set in
`~/.gradle/gradle.properties`, then `./gradlew assembleRelease`. State plainly that the production
Nothing key comes from Nothing's developer programme and must never be committed.

**Verify**: `grep -n "glyp.nothingKey" README.md` → ≥1 match.

## Test plan

No unit tests change (`./gradlew :app:testDebugUnitTest` must still pass). The real test is step 4:
the R8-shrunk build still connects to the Glyph service and still loads existing projects (open one
that existed before installing — that exercises the `ProjectMeta` serializer).

## Done criteria

- [ ] `grep -n 'android:value="test"' app/src/main/AndroidManifest.xml` → no matches
- [ ] `./gradlew assembleDebug assembleRelease :app:testDebugUnitTest` → `BUILD SUCCESSFUL` (no secrets supplied)
- [ ] Signed release builds with the throwaway keystore and verifies with `apksigner`
- [ ] `git status --short` shows no keystore, no `.jks`, no passwords; only in-scope files
- [ ] Step 4 done on a device, or explicitly reported as pending
- [ ] `plans/README.md` status row updated

## STOP conditions

- Any secret value would have to be written into a tracked file.
- The release build crashes at startup or shows `NO GLYPH ACCESS` / stays on `CONNECTING` while the
  debug build is `● LIVE` on the same phone — the keep rules are insufficient; report the logcat
  instead of widening rules blindly.
- Projects created by the debug build fail to load in the release build.

## Maintenance notes

- Any new reflection or binder-based dependency needs a keep rule here; verify with a release build
  on a device, not just unit tests.
- When the Nothing production key is obtained, it goes only in `~/.gradle/gradle.properties` (or a CI
  secret); rotate it if it is ever committed.
