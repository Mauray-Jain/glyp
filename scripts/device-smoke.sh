#!/usr/bin/env bash
# End-to-end check of Glyp on a connected Nothing Phone (3a): install, import a generated song,
# play it and check frames reach the Glyph service, background and resume, export, clean up.
#
# Usage: scripts/device-smoke.sh [--apk <path>] [--keep]
#   --apk   APK to install (default: the debug APK)
#   --keep  leave the test project, song and ringtone on the phone
#
# Never changes phone settings: Nothing OS switches are only read, and reported as warnings.
set -euo pipefail

PKG=com.mauray.glyp
APK="$(cd "$(dirname "$0")/.." && pwd)/app/build/outputs/apk/debug/app-debug.apk"
KEEP=0
NAME=glyp-smoke
SONG_DIR=/sdcard/Music/GlypSmoke
RINGTONE=/sdcard/Ringtones/Glyp/${NAME}.ogg

while [ $# -gt 0 ]; do
    case "$1" in
        --apk) APK="$2"; shift 2 ;;
        --keep) KEEP=1; shift ;;
        -h|--help) sed -n '2,9p' "$0"; exit 0 ;;
        *) echo "Unknown option: $1" >&2; exit 2 ;;
    esac
done

WORK="$(mktemp -d)"
trap 'rm -rf "${WORK}"' EXIT
FAILED=()

pass() { echo "PASS ${1}"; }
fail() { echo "FAIL ${1}"; FAILED+=("${1}"); }
warn() { echo "WARN ${1}"; }
die() { echo "ERROR ${1}" >&2; exit 1; }

# Runs a check: `check "name" command args…` prints PASS or FAIL and keeps going.
check() {
    local name="$1"; shift
    if "$@"; then pass "${name}"; else fail "${name}"; fi
}

# --- Preconditions -----------------------------------------------------------------------------

command -v adb > /dev/null || die "adb not found on PATH"
command -v ffmpeg > /dev/null || die "ffmpeg not found on PATH (used to generate the test song)"
devices=$(adb devices | awk 'NR > 1 && $2 == "device"' | wc -l)
[ "${devices}" -eq 1 ] || die "need exactly one phone attached over adb (found ${devices})"
[ -f "${APK}" ] || die "APK not found: ${APK} (run ./gradlew assembleDebug)"
model=$(adb shell getprop ro.product.model | tr -d '\r')
if [ "${model}" != "A059" ]; then
    warn "model is '${model}', not a Phone (3a) (A059): the Studio will show PREVIEW, no real lights"
fi
if adb shell dumpsys window 2>/dev/null | grep -q "mDreamingLockscreen=true\|isKeyguardShowing=true"; then
    die "the phone is locked: unlock it (this script never enters a PIN)"
fi

# --- UI helpers --------------------------------------------------------------------------------

# Dumps the current screen to ${WORK}/ui.xml; uiautomator sometimes fails or is stale, so retry.
dump() {
    local _
    for _ in 1 2 3; do
        if adb shell uiautomator dump /sdcard/glyp-ui.xml > /dev/null 2>&1 &&
            adb pull /sdcard/glyp-ui.xml "${WORK}/ui.xml" > /dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# Centre of the first element whose text or content-desc is exactly $1, as "x y".
centre_of() {
    python3 - "${WORK}/ui.xml" "$1" <<'PY'
import re, sys
import xml.etree.ElementTree as ET
want = sys.argv[2]
for node in ET.parse(sys.argv[1]).iter("node"):
    if want in (node.get("text"), node.get("content-desc")):
        x0, y0, x1, y1 = map(int, re.findall(r"\d+", node.get("bounds")))
        print((x0 + x1) // 2, (y0 + y1) // 2)
        break
PY
}

has_text() { dump && [ -n "$(centre_of "$1")" ]; }

# Waits up to $2 seconds for text $1 to be on screen.
wait_text() {
    local deadline=$((SECONDS + $2))
    while [ "${SECONDS}" -lt "${deadline}" ]; do
        has_text "$1" && return 0
        sleep 1
    done
    return 1
}

# Taps the element showing $1, retrying a fresh dump up to 3 times.
tap_text() {
    local _ pos
    for _ in 1 2 3; do
        if dump; then
            pos=$(centre_of "$1")
            if [ -n "${pos}" ]; then
                # shellcheck disable=SC2086 # pos is "x y"
                adb shell input tap ${pos}
                return 0
            fi
        fi
        sleep 1
    done
    echo "  (not found on screen: '$1')" >&2
    return 1
}

# Long-presses the element showing $1.
long_press_text() {
    local pos x y
    dump || return 1
    pos=$(centre_of "$1")
    [ -n "${pos}" ] || return 1
    read -r x y <<< "${pos}"
    adb shell input swipe "${x}" "${y}" "${x}" "${y}" 800
}

# The Studio's Glyph status label, or empty.
glyph_status() {
    local label
    dump || return 0
    for label in "● LIVE" "CONNECTING" "GLYPHS OFF" "SCREEN UP" "NO GLYPH ACCESS" "PREVIEW"; do
        if [ -n "$(centre_of "${label}")" ]; then echo "${label}"; return 0; fi
    done
}

# Waits up to $1 seconds for a status that means "connected"; prints the last one seen.
wait_connected() {
    local deadline=$((SECONDS + $1)) status=""
    while [ "${SECONDS}" -lt "${deadline}" ]; do
        status=$(glyph_status)
        case "${status}" in
            "● LIVE"|"GLYPHS OFF"|"SCREEN UP") echo "${status}"; return 0 ;;
            "PREVIEW") if [ "${model}" != "A059" ]; then echo "${status}"; return 0; fi ;;
        esac
        sleep 1
    done
    echo "${status}"
    return 1
}

# Clears the logs that frame and bind counts read; the crash buffer is kept for the whole run.
clear_log() { adb logcat -b main -b system -c; }

glyph_frames() { adb logcat -d -b main -v brief | grep -c 'GlyphService.*finalColors' || true; }

launch() { adb shell am start -W -n "${PKG}/.MainActivity" > /dev/null; }

# --- Checks ------------------------------------------------------------------------------------

echo "== Install and launch"
adb install -r "${APK}" > /dev/null
adb logcat -b crash -c
clear_log
launch
check "app starts on Glyp home" wait_text "Glyp" 10

echo "== Test song"
ffmpeg -loglevel error -y -f lavfi \
    -i "aevalsrc=0.6*sin(2*PI*60*t)*exp(-12*mod(t\,0.5)):s=44100" -t 10 "${WORK}/${NAME}.mp3"
adb shell mkdir -p "${SONG_DIR}"
adb push "${WORK}/${NAME}.mp3" "${SONG_DIR}/${NAME}.mp3" > /dev/null
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE \
    -d "file://${SONG_DIR}/${NAME}.mp3" > /dev/null

echo "== Import"
import_song() {
    tap_text "New from song" &&
        tap_text "Auto compose" &&
        tap_text "Search" &&
        adb shell input text "${NAME}" &&
        adb shell input keyevent 66 &&
        sleep 2 &&
        tap_text "${NAME}.mp3" &&
        wait_text "Compose" 30 &&
        wait_text "Pulse" 30
}
check "import and auto compose ${NAME}.mp3" import_song

echo "== Glyph connection"
if status=$(wait_connected 10); then pass "Glyph status is '${status}'"; else fail "Glyph status is '${status}' after 10 s"; fi
switch=$(adb shell settings get global led_effect_enable | tr -d '\r')
upward=$(adb shell settings get global glyph_screen_upward_state | tr -d '\r')
if [ "${switch}" = "0" ]; then
    warn "Nothing OS has the Glyph Interface off (tile or bedtime schedule): frames arrive, LEDs stay dark"
fi
if [ "${upward}" = "1" ]; then warn "the phone lies screen-up: Nothing OS keeps the Glyphs dark"; fi

echo "== Play"
clear_log
play_and_count() {
    tap_text "Play" && sleep "$1" && tap_text "Pause" && [ "$(glyph_frames)" -gt 0 ]
}
check "frames reach the Glyph service while playing" play_and_count 2

echo "== Background and resume"
clear_log
adb shell input keyevent 3
sleep 2
launch
if status=$(wait_connected 5); then pass "status recovers after Home ('${status}')"; else fail "status is '${status}' 5 s after returning"; fi
binds=$(adb logcat -d -b main -b system -v brief | grep -c "bindService.*client ${PKG}" || true)
check "no second bind to the Glyph service (binds: ${binds})" [ "${binds}" -eq 0 ]
clear_log
check "frames reach the Glyph service after resuming" play_and_count 1

echo "== Export"
export_ringtone() {
    tap_text "Export ringtone" && tap_text "Export" && wait_text "Saved" 30
}
check "export finishes" export_ringtone
check "ringtone file exists" adb shell test -f "${RINGTONE}"
if command -v ffprobe > /dev/null && adb pull "${RINGTONE}" "${WORK}/${NAME}.ogg" > /dev/null 2>&1; then
    composer=$(ffprobe -v error -show_entries stream_tags=COMPOSER -of csv=p=0 "${WORK}/${NAME}.ogg")
    check "ringtone carries Glyph light data (COMPOSER '${composer}')" [ "${composer}" = "v1-Asteroids Glyph Composer" ]
fi

echo "== Crashes"
crashes=$(adb logcat -d -b crash | grep -c "Process: ${PKG}" || true)
check "no crash for ${PKG}" [ "${crashes}" -eq 0 ]

if [ "${KEEP}" -eq 0 ]; then
    echo "== Clean up"
    tap_text "Done" || true
    tap_text "Back" || true
    if long_press_text "${NAME}" && tap_text "Delete selected"; then
        # The project is only deleted once its Undo snackbar has gone.
        sleep 12
        if has_text "${NAME}"; then warn "project '${NAME}' is still listed"; fi
    else
        warn "could not delete project '${NAME}'; remove it by hand"
    fi
    adb shell rm -rf "${SONG_DIR}" "${RINGTONE}" /sdcard/glyp-ui.xml
fi

echo
if [ "${#FAILED[@]}" -gt 0 ]; then
    echo "${#FAILED[@]} CHECK(S) FAILED:"
    printf '  %s\n' "${FAILED[@]}"
    exit 1
fi
echo "ALL CHECKS PASSED"
