#!/usr/bin/env bash
# Smoke test for the shrunk (R8) release APK: fresh install, sign in with the remote, open Home,
# then play an HEVC + ASS title through the full Media3/libass/FFmpeg stack. Fails on any crash.
# Usage: android_release_smoke.sh <release-apk> <out-dir> <token>
set -uo pipefail
APK="$1"; OUT="$2"; TOKEN="$3"
PKG=com.auroratv.android
ACT=com.auroratv.android.MainActivity
URL=http://localhost:8096
A="Authorization: MediaBrowser Client=\"Aurora CI\", Device=\"CI\", DeviceId=\"aurora-ci\", Version=\"1.0.0\", Token=\"$TOKEN\""
mkdir -p "$OUT"
UID_=$(curl -sf "$URL/Users/Me" -H "$A" | jq -r .Id)
EP1=$(curl -sf "$URL/Items?userId=$UID_&recursive=true&includeItemTypes=Episode&sortBy=SortName" -H "$A" | jq -r '.Items[0].Id')
GLASS=$(curl -sf "$URL/Items?userId=$UID_&recursive=true&includeItemTypes=Movie&searchTerm=Glass%20Meridian" -H "$A" | jq -r '.Items[0].Id')

shot() { adb exec-out screencap -p > "$OUT/$1.png"; echo "captured $1"; }
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 1.2; done; }

adb install -r "$APK" || exit 1
echo "Installed size: $(du -h "$APK" | cut -f1)"
adb logcat -c
adb shell am start -n "$PKG/$ACT"
sleep 12; shot release-01-connect
adb shell input text "10.0.2.2:8096"; sleep 1; key KEYCODE_ENTER
sleep 8
adb shell input text "aurora"; key KEYCODE_ENTER
adb shell input text "aurora-ci"; key KEYCODE_ENTER
sleep 15; shot release-02-home
open() { adb shell am force-stop "$PKG"; sleep 1; adb shell am start -n "$PKG/$ACT" --es route "$1"; }
open "player:$EP1"; sleep 20; shot release-03-player-ass
key KEYCODE_DPAD_UP; sleep 1; key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT; sleep 0.4; shot release-04-scrub
open "player:$GLASS"; sleep 20; shot release-05-player-hevc

adb logcat -d > "$OUT/logcat-release.txt"
if grep -qE "FATAL EXCEPTION|UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError|SerializationException" "$OUT/logcat-release.txt"; then
  echo "Release build failed at runtime:"
  grep -E -A25 "FATAL EXCEPTION|UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError|SerializationException" "$OUT/logcat-release.txt" | head -80
  exit 1
fi
grep -E "ExoEngine|Negotiator|libass" "$OUT/logcat-release.txt" | tail -20 || true
echo "Release smoke test passed"
