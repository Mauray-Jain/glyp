# Plan 013: An exported Glyph ringtone can also be set as the notification or alarm sound

> **Executor instructions**: Follow this plan step by step. Run every
> verification command and confirm the expected result before moving to the
> next step. If anything in the "STOP conditions" section occurs, stop and
> report — do not improvise. When done, update the status row for this plan
> in `plans/README.md` — unless a reviewer dispatched you and told you they
> maintain the index.
>
> **Drift check (run first)**: `git diff --stat f1c86f3..HEAD -- app/src/main/java/com/mauray/glyp/export/RingtoneInstaller.kt app/src/main/java/com/mauray/glyp/ui/studio/StudioScreen.kt app/src/test/java/com/mauray/glyp/ui/studio/StudioSessionTest.kt`
> If any in-scope file changed since this plan was written, compare the
> "Current state" excerpts against the live code before proceeding; on a
> mismatch, treat it as a STOP condition.

## Status

- **Priority**: P3
- **Effort**: S
- **Risk**: LOW
- **Depends on**: none (plan 004 edits the same file; land one, rebase the other)
- **Category**: direction
- **Planned at**: commit `f1c86f3`, 2026-11-11

## Why this matters

Glyp exports Glyph ringtones (audio + light data for the Nothing Phone (3a)) and, after export,
offers **Set as ringtone**. The exported file is already registered with Android as usable for
notifications and alarms too (`IS_NOTIFICATION = 1`, `IS_ALARM = 1`), so it appears in those system
pickers — but the app only offers the ringtone shortcut. Adding "Notification" and "Alarm" next to
it is a small change that reuses the same permission flow.

**Unverified assumption**: whether Nothing OS plays the *lights* (not just the sound) for a
notification or alarm sound that carries Glyph data. Step 4 checks it on a device; the feature is
still useful as a sound shortcut if it doesn't, but the copy must not promise lights then.

## Current state

- `export/RingtoneInstaller.kt` (at `f1c86f3`):
  ```kotlin
  /** A ringtone that has been saved to the device. */
  interface Ringtone {
      /**
       * Makes this the default ringtone. Returns false, after opening the "Modify system
       * settings" screen, when the app is not allowed to yet.
       */
      fun setAsDefault(): Boolean
  }
  ...
  private fun ringtoneAt(uri: Uri) = object : Ringtone {
      override fun setAsDefault() = setAsDefault(uri)
  }

  private fun setAsDefault(uri: Uri): Boolean {
      if (!Settings.System.canWrite(appContext)) {
          val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${appContext.packageName}"))
          appContext.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
          return false
      }
      RingtoneManager.setActualDefaultRingtoneUri(appContext, RingtoneManager.TYPE_RINGTONE, uri)
      return true
  }
  ```
  Inserted entries set `IS_RINGTONE`, `IS_NOTIFICATION`, `IS_ALARM` = 1.
- `ui/studio/StudioScreen.kt` — the "Saved" stage of `ExportDialog`:
  ```kotlin
  /** [note] says how the last "Set as ringtone" went, if it was tapped. */
  data class Saved(val ringtone: Ringtone, val note: String? = null) : ExportStage
  ...
  onSetRingtone = { saved ->
      // Said in the dialog: a snackbar would sit behind it, or fade while Settings is open.
      val note = if (saved.ringtone.setAsDefault()) {
          "It's now your ringtone."
      } else {
          "Allow Glyp to modify system settings, then come back and tap again."
      }
      export = saved.copy(note = note)
  },
  ```
  and the confirm button: `is ExportStage.Saved -> PillButton("Set as ringtone", { onSetRingtone(stage) }, height = 48.dp)`;
  the body text: "Find it in Settings → Sound → Ringtone, or set it now and the lights play on your next call."
- Fakes implementing `Ringtone` in tests: `app/src/test/java/com/mauray/glyp/ui/studio/StudioSessionTest.kt`
  (`object : Ringtone { override fun setAsDefault() = true }`).
- Choice chips: `TogglePill(text, selected, onClick)` in `ui/components/Nothing.kt`.

## Commands you will need

| Purpose | Command | Expected on success |
|---|---|---|
| Unit tests | `./gradlew :app:testDebugUnitTest` | `BUILD SUCCESSFUL` |
| Debug build | `./gradlew assembleDebug` | `BUILD SUCCESSFUL` |
| Current defaults (device) | `adb shell settings get system ringtone; adb shell settings get system notification_sound; adb shell settings get system alarm_alert` | three URIs |

## Scope

**In scope**: `export/RingtoneInstaller.kt`, `ui/studio/StudioScreen.kt` (Saved dialog),
`StudioSessionTest.kt` (fake update), `README.md` (Export bullet).

**Out of scope**: MediaStore insertion flags (already right), the export pipeline, permissions
(same `WRITE_SETTINGS` flow).

## Git workflow

- Branch: `advisor/013-notification-alarm`; one commit.
- Do NOT push or open a PR unless the operator instructed it.
- **Never change the operator's actual ringtone/notification/alarm on a device without asking** —
  step 4 needs explicit permission and must restore the previous values afterwards.

## Steps

### Step 1: `Ringtone.setAsDefault(kind)`

Add `enum class SoundKind(val type: Int) { RINGTONE(TYPE_RINGTONE), NOTIFICATION(TYPE_NOTIFICATION), ALARM(TYPE_ALARM) }`
(`RingtoneManager` constants) and change the interface to `fun setAsDefault(kind: SoundKind =
SoundKind.RINGTONE): Boolean`; the private helper uses `kind.type`. Update the test fake.

**Verify**: `./gradlew :app:testDebugUnitTest assembleDebug` → `BUILD SUCCESSFUL`.

### Step 2: Saved dialog

Replace the single "Set as ringtone" button with three `TogglePill`s — Ringtone / Notification /
Alarm — plus one "Set" `PillButton` that applies the chosen kind (default Ringtone). Notes:
"It's now your ringtone." / "…your notification sound." / "…your alarm sound."; permission note
unchanged. Change the body text to "Find it in Settings → Sound, or set it now." (no promise about
lights until step 4 confirms).

**Verify**: `./gradlew :app:verifyPaparazziDebug assembleDebug` → `BUILD SUCCESSFUL`.

### Step 3: README

Export bullet: "…and can set it as the ringtone, notification or alarm sound."

**Verify**: `grep -n "notification or alarm" README.md` → 1 match.

### Step 4 (device, only with the operator's OK)

Record the three current defaults (command above). With permission granted to Glyp, set an exported
file as the notification sound, trigger a notification (e.g. `adb shell cmd notification post -S
bigtext -t "test" glyptest "hello"`), and report whether the Glyphs played the light data. Then
restore all three defaults with `adb shell settings put system <key> <previous URI>`.

## Test plan

Fake update in `StudioSessionTest` keeps compiling. No new pure logic beyond the enum mapping, so no
new unit tests; the device check in step 4 is the meaningful test.

## Done criteria

- [ ] `./gradlew :app:testDebugUnitTest :app:verifyPaparazziDebug assembleDebug` → `BUILD SUCCESSFUL`
- [ ] Dialog offers Ringtone / Notification / Alarm
- [ ] Step 4 result reported (lights play or not), defaults restored, or "not run: no permission"
- [ ] `plans/README.md` status row updated

## STOP conditions

- The operator hasn't approved changing device sound settings — skip step 4 and report.
- Nothing OS ignores Glyph data for notifications **and** the operator wanted the feature for the
  lights — report before polishing the UI.

## Maintenance notes

- If step 4 shows lights do play for notifications, update the body text to say so.
- Plan 004's overwrite-in-place keeps the same URI, so a notification sound set here also updates
  when the user re-exports under the same name.
