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
EP3=$(curl -sf "$URL/Items?userId=$UID_&recursive=true&includeItemTypes=Episode&sortBy=SortName" -H "$A" | jq -r '.Items[2].Id')
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
key KEYCODE_DPAD_DOWN; sleep 2; shot android-04a-brand-tiles
key KEYCODE_DPAD_DOWN; sleep 2; shot android-04-rail-focus
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER; sleep 8; shot android-05-detail
key KEYCODE_BACK; sleep 3; shot android-06-back-focus-restored
key KEYCODE_BACK; sleep 2; shot android-07-back-to-sidebar
# Sidebar: Down to "Up Next", Select switches section and hands focus to its content.
key KEYCODE_DPAD_DOWN; sleep 1; shot android-07b-sidebar-down
key KEYCODE_DPAD_CENTER; sleep 6; shot android-07c-sidebar-select

# Deep links (session is restored from the Keystore vault on relaunch).
open() { adb shell am force-stop "$PKG"; sleep 1; adb shell am start -n "$PKG/$ACT" --es route "$1"; }
# Turn subtitles on (English, always) the same way the Settings screen persists them.
adb shell am force-stop "$PKG"
echo '{"theme":"Dark","subtitleMode":"Always","preferredSubtitleLanguage":"eng"}' | adb shell "run-as $PKG sh -c 'cat > files/settings.json'"
adb shell "run-as $PKG cat files/settings.json" || true
open "detail:$SERIES"; sleep 14; key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; shot android-08-series-detail
open "player:$EP1"; sleep 22; shot android-09-player-ass-typesetting
sleep 4; shot android-10-player-ass-later
key KEYCODE_DPAD_UP; sleep 2; shot android-11-player-controls
key KEYCODE_BACK; sleep 1
open "player:$EP3"; sleep 9; key KEYCODE_DPAD_DOWN; sleep 3; shot android-12-player-episode-browser
key KEYCODE_DPAD_RIGHT; sleep 1; shot android-13-player-episode-focus
# Scrubbing: run the key presses and the capture inside the device in one shell, because each
# separate `adb shell input` takes seconds on a loaded emulator and the controls would time out.
open "player:$SEVEN"; sleep 9
adb shell 'input keyevent KEYCODE_DPAD_UP; sleep 0.3; input keyevent KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT; sleep 0.2; screencap -p /sdcard/scrub.png'
adb pull -q /sdcard/scrub.png "$OUT/android-16-player-scrubbing.png" >/dev/null && echo "captured android-16-player-scrubbing"
# Detail page → Play with the remote: playback is prepared while the page is open ("Ready to play in … ms").
open "detail:$SEVEN"; sleep 6; key KEYCODE_DPAD_CENTER; sleep 8; shot android-13b-play-from-detail; key KEYCODE_BACK; sleep 2
open "player:$GLASS"; sleep 22; shot android-14-player-hevc-main10-ass
open "brand:netflix"; sleep 10; shot android-17-brand-hub
open "section:Settings"; sleep 10; key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; sleep 2; shot android-15a-settings-ratings
key KEYCODE_DPAD_DOWN; sleep 2; shot android-15-about-capabilities

# ---- MPV player: the same subtitle-heavy titles through libmpv (switched with the `player` extra) ----
# Start EP1 from the beginning (the ExoPlayer run above left a resume point in it).
curl -sf -X POST "$URL/UserItems/$EP1/UserData?userId=$UID_" -H "$A" -H "Content-Type: application/json" -d '{"PlaybackPositionTicks":0}' >/dev/null || true
openmpv() { adb shell am force-stop "$PKG"; sleep 1; adb shell am start -n "$PKG/$ACT" --es player mpv --es route "$1"; }
openmpv "player:$EP1"; sleep 24; shot android-17-mpv-anime-ass
sleep 4; shot android-18-mpv-anime-ass-later
openmpv "player:$GLASS"; sleep 24; shot android-19-mpv-hevc-main10-styled-ass
openmpv "player:$SEVEN"; sleep 16; shot android-20-mpv-direct-play-mkv
key KEYCODE_DPAD_DOWN; sleep 2; shot android-21-mpv-info
adb shell am force-stop "$PKG"; adb shell am start -n "$PKG/$ACT" --es player exoplayer; sleep 6

adb logcat -d > "$OUT/logcat-full.txt"
# mpv's own warnings/errors (tag "mpv"), with any credentials redacted, for diagnosing the MPV runs.
grep -E " [VDIWE] mpv +:" "$OUT/logcat-full.txt" | grep -E ":(error|warn|fatal)\]|Opening|EOF|end of file|HTTP|Server returned|cplayer:info|demux" \
  | sed -E 's/(api_key|ApiKey|X-Emby-Token)[=:] ?[A-Za-z0-9]+/\1=REDACTED/gI; s/Token=\\?"?[A-Za-z0-9]+/Token=REDACTED/g' | head -400 > "$OUT/logcat-mpv.txt" || true
grep -E "Negotiator|Caps|Capabilities|ExoEngine|MpvEngine|font attachments|libass|AndroidRuntime|FATAL|AuroraKeys|AuroraFocus|Ready to play|Prewarm|DolbyVision" "$OUT/logcat-full.txt" > "$OUT/logcat-aurora.txt" || true
echo "---- Key / focus trace ----"; grep -E "AuroraKeys" "$OUT/logcat-aurora.txt" | grep -E "DPAD|BACK|ENTER" | head -80
echo "---- Time from Play to engine start ----"; grep -E "Ready to play" "$OUT/logcat-aurora.txt" | sed -E "s/^.*Ready/Ready/" || true
echo "---- Aurora log excerpt ----"; grep -vE "AuroraKeys|AuroraFocus" "$OUT/logcat-aurora.txt" | tail -40
if grep -q "FATAL EXCEPTION" "$OUT/logcat-full.txt"; then echo "App crashed"; grep -A30 "FATAL EXCEPTION" "$OUT/logcat-full.txt" | head -60; exit 1; fi
