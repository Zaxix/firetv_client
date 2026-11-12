#!/usr/bin/env bash
# Android TV emulator end-to-end run: install the debug APK, sign in to the CI Jellyfin server with the
# remote (adb key events), browse with the D-pad, open a detail page, and play media with styled ASS.
# Usage: android_e2e.sh <apk> <out-dir> <token>
set -uo pipefail
APK="$1"; OUT="$2"; TOKEN="$3"
PKG=com.auroratv.android.debug
ACT=com.auroratv.android.MainActivity
URL=http://localhost:8096
A="Authorization: MediaBrowser Client=\"Aurora CI\", Device=\"CI\", DeviceId=\"aurora-ci\", Version=\"1.0.0\", Token=\"$TOKEN\""
mkdir -p "$OUT"
UID_=$(curl -sf "$URL/Users/Me" -H "$A" | jq -r .Id)
q() { curl -sf "$URL/Items?userId=$UID_&recursive=true&$1" -H "$A" | jq -r '.Items[0].Id'; }
GLASS=$(q "includeItemTypes=Movie&searchTerm=Glass%20Meridian")
SERIES=$(q "includeItemTypes=Series")
EP1=$(q "includeItemTypes=Episode&sortBy=SortName")
SEVEN=$(q "includeItemTypes=Movie&searchTerm=Seven%20Rivers")

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 1.2; done; }

adb install -r "$APK"
adb shell settings put system screen_off_timeout 1800000
adb shell settings put secure screensaver_enabled 0 || true
adb logcat -c

adb shell am start -n "$PKG/$ACT"
sleep 15; shot android-01-connect
# Remote/IME input into the focused address field, then Enter (ImeAction.Go → connect).
adb shell input text "10.0.2.2:8096"; sleep 1
key KEYCODE_ENTER
sleep 10; shot android-02-login
adb shell input text "aurora"; key KEYCODE_ENTER
adb shell input text "aurora-ci"; key KEYCODE_ENTER
sleep 20; shot android-03-home
key KEYCODE_DPAD_DOWN; sleep 2; shot android-04-rail-focus
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER; sleep 8; shot android-05-detail
key KEYCODE_BACK; sleep 3; shot android-06-back-focus-restored
key KEYCODE_DPAD_LEFT; sleep 2; shot android-07-sidenav-expanded

# Deep links (session is restored from the Keystore vault on relaunch).
open() { adb shell am force-stop "$PKG"; sleep 1; adb shell am start -n "$PKG/$ACT" --es route "$1"; }
open "detail:$SERIES"; sleep 14; key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; shot android-08-series-detail
open "player:$EP1"; sleep 22; shot android-09-player-ass-typesetting
sleep 4; shot android-10-player-ass-later
key KEYCODE_DPAD_UP; sleep 2; shot android-11-player-controls
open "player:$SEVEN"; sleep 20; key KEYCODE_DPAD_UP; sleep 1; shot android-12-player-multitrack
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER; sleep 2; shot android-13-player-subtitle-panel
open "player:$GLASS"; sleep 22; shot android-14-player-hevc-main10-ass
open "section:Settings"; sleep 10; key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; sleep 2; shot android-15-about-capabilities

adb logcat -d > "$OUT/logcat-full.txt"
grep -E "Negotiator|Caps|Capabilities|ExoEngine|libass|AndroidRuntime|FATAL" "$OUT/logcat-full.txt" > "$OUT/logcat-aurora.txt" || true
echo "---- Aurora log excerpt ----"; tail -60 "$OUT/logcat-aurora.txt"
if grep -q "FATAL EXCEPTION" "$OUT/logcat-full.txt"; then echo "App crashed"; grep -A30 "FATAL EXCEPTION" "$OUT/logcat-full.txt" | head -60; exit 1; fi
