# Glyph Studio

Light compositions and Glyph ringtones for the Nothing Phone (3a) / (3a) Pro.

- **Auto compose**: pick a song; it is analysed (FFT bands + onset detection) and the lights are
  generated in one of four styles: Pulse, Spectrum, Flow, Classic. Or start blank.
- **Edit**: timeline of all 36 zones with a waveform, live preview on screen and on the real
  lights, fill / fade / pulse / sweep tools, regenerate a selection, undo/redo.
- **Record**: five pads in the Glyph Composer layout; jam on the lights, or record hits over
  the song (layer or replace).
- **Export**: writes an Ogg Opus ringtone with the light data in its tags (Nothing's ringtone
  format) to `Ringtones/Glyph Studio`, and can set it as the default ringtone.

## Build

Needs JDK 17 and an Android SDK with platform 37 and build-tools 37.0.0 (point
`local.properties` at it with `sdk.dir=...`).

Nothing's Glyph SDK isn't redistributed in this repo; fetch it once from Nothing's
[Glyph Developer Kit](https://github.com/Nothing-Developer-Programme/Glyph-Developer-Kit):

```bash
./scripts/fetch-glyph-sdk.sh
```

```bash
./gradlew assembleDebug
```

## Run on the phone

Third-party apps can only drive the Glyph lights while Glyph debug mode is on (it switches off
after 48 hours):

```bash
adb shell settings put global nt_glyph_interface_debug_enable 1
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Layout

| Package | What |
|---|---|
| `glyph` | Zone map (C 0–19, A 20–30, B 31–35) and the Glyph SDK wrapper |
| `composer` | FFT, audio analysis, automatic composition styles |
| `studio` | Hand-editing tools and the Record-mode pad engine |
| `export` | Ringtone tags, Opus encoding, Ogg re-tagging, MediaStore publish |
| `ui` | Compose screens and the Nothing-style components |

## Fonts

Bundled under the SIL Open Font License 1.1 (from github.com/google/fonts):
[Newsreader](https://fonts.google.com/specimen/Newsreader) for headlines,
[Inter](https://fonts.google.com/specimen/Inter) for body text,
[IBM Plex Mono](https://fonts.google.com/specimen/IBM+Plex+Mono) for small labels, and
[Doto](https://fonts.google.com/specimen/Doto) for large dot-matrix numerals.

## Tests and screenshots

```bash
./gradlew :app:testDebugUnitTest
```

`./gradlew :app:recordPaparazziDebug` renders the screens at device resolution to
`app/src/test/snapshots/images/` without a device.
