# Plan 006: Cloud backup stays within Android's limit; device-to-device transfer keeps projects

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- app/src/main/AndroidManifest.xml app/src/main/res/xml/`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P2
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none
- **Category**: bug (data)
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

Glyp stores each project in the app's private files as `files/projects/<uuid>/` containing
`project.json`, `lights.bin` and **a full copy of the imported song** (`audio.mp3`, `audio.m4a`, …,
up to 10 minutes long). The manifest says `android:allowBackup="true"` with no rules, so Android
Auto Backup tries to upload all of it to the user's Google Drive. Auto Backup has a **25 MB
per-app quota**; once a backup would exceed it, Android stops backing the app up entirely — so a
user with two or three songs gets *no* backup at all, silently. Android Lint flags the same gap
(`DataExtractionRules` warning).

Decision taken in this plan (recommended; see STOP conditions if the operator disagrees):
**exclude `projects/` from cloud backup, keep it for device-to-device transfer.** Backing up only
`project.json` + `lights.bin` without the audio would restore projects that can't be opened, which
is worse than not restoring them. Device-to-device transfer (setting up a new phone from the old
one) has no such quota, so projects move with the user there. Exported ringtones live in public
`Ringtones/Glyp` and are not affected either way.

## Current state

- `app/src/main/AndroidManifest.xml` lines 8–14 at `f1c86f3`:
  ```xml
  <application
      android:name=".GlypApp"
      android:allowBackup="true"
      android:icon="@mipmap/ic_launcher"
      android:label="@string/app_name"
      android:supportsRtl="true"
      android:theme="@style/Theme.Glyp">
  ```
- `app/build.gradle.kts`: `minSdk = 34`, so only the Android 12+ attribute
  `android:dataExtractionRules` is needed (not the older `android:fullBackupContent`).
- There is no `app/src/main/res/xml/` directory yet.
- `GlypApp.kt`: `val store by lazy { ProjectStore(File(filesDir, "projects")) }` — the folder is
  `filesDir/projects`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Lint | `./gradlew :app:lintDebug` | `BUILD SUCCESSFUL` |
| Lint check for this issue | `grep -c "DataExtractionRules" app/build/reports/lint-results-debug.txt` | `0` after the fix (it is `1`+ before) |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |
| Unit tests | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |

## Scope

**In scope**:
- `app/src/main/res/xml/data_extraction_rules.xml` (create)
- `app/src/main/AndroidManifest.xml` (one attribute)

**Out of scope**:
- Changing where projects are stored.
- Turning `allowBackup` off — that would also drop device-to-device transfer.

## Git workflow

- Branch: `advisor/006-backup-rules`; one commit, imperative summary + why.
- Do NOT push or open a PR unless the operator instructed it.

## Steps

### Step 1: Write the rules

Create `app/src/main/res/xml/data_extraction_rules.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!--
  Projects keep a full copy of each song, so they blow through Auto Backup's 25 MB quota (and then
  nothing is backed up at all). Without the audio a project can't be opened, so cloud backup skips
  them entirely; device-to-device transfer has no quota and keeps everything.
-->
<data-extraction-rules>
    <cloud-backup>
        <exclude domain="file" path="projects" />
    </cloud-backup>
    <device-transfer />
</data-extraction-rules>
```

(An empty `<device-transfer />` keeps the default: everything is transferred.)

**Verify**: `python3 -c "import xml.dom.minidom,sys;xml.dom.minidom.parse('app/src/main/res/xml/data_extraction_rules.xml')"` → exit 0.

### Step 2: Reference it from the manifest

Add `android:dataExtractionRules="@xml/data_extraction_rules"` to `<application>`, directly under
`android:allowBackup="true"`.

**Verify**: `./gradlew :app:lintDebug assembleDebug` → `BUILD SUCCESSFUL`, and the lint grep from
the table → `0`.

## Test plan

No unit tests (manifest/resource only). Lint is the check. Optional, on a phone with a backup
transport configured: `adb shell bmgr backupnow com.mauray.glyp` → "Backup finished with result:
Success" even with several projects present.

## Done criteria

- [ ] `data_extraction_rules.xml` exists and parses
- [ ] Manifest references it
- [ ] `./gradlew :app:lintDebug assembleDebug :app:testDebugUnitTest` → `BUILD SUCCESSFUL`
- [ ] Lint report has no `DataExtractionRules` entry
- [ ] `plans/README.md` status row updated

## STOP conditions

- The operator wants projects in cloud backup anyway (e.g. small ringtone-length songs only) —
  that needs a different design (back up `lights.bin` + `project.json` and re-link audio on
  restore); report back rather than guessing.
- Lint reports a new error caused by the rules file.

## Maintenance notes

- If projects ever stop storing the audio copy (e.g. referencing the user's file instead), revisit:
  then the projects folder would be small enough to back up.
- Anything new written to `filesDir` outside `projects/` is backed up by default — keep it small.
