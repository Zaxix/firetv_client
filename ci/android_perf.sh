#!/usr/bin/env bash
# Performance audit rounds on the release APK (already installed and signed in by
# android_release_smoke.sh). Each round, for a level (`--ei perfLevel`: 0 = before the audit, 1 = now):
#   1. cold start to Home with the UI-thread probe and the API request trace on (`--ez perfTrace true`);
#   2. open five films/shows from Home and go back each time (details pages: shown / loaded, requests);
#   3. a series: switch seasons there and back (Season shown, requests);
#   4. memory (PSS) after all that, and repeated API requests (same path and query) in the round.
# Levels alternate (0 1 0 1 ...) so a slower or faster stretch of the runner affects both alike.
# Usage: android_perf.sh <out-dir> <token>
set -uo pipefail
OUT="$1"; TOKEN="$2"
PKG=com.auroratv.android
ACT=com.auroratv.android.MainActivity
URL=http://localhost:8096
A="Authorization: MediaBrowser Client=\"Aurora CI\", Device=\"CI\", DeviceId=\"aurora-ci\", Version=\"1.0.0\", Token=\"$TOKEN\""
UID_=$(curl -sf "$URL/Users/Me" -H "$A" | jq -r .Id)
# Zephyr Bay: the CI series with two seasons.
SERIES=$(curl -sf "$URL/Items?userId=$UID_&recursive=true&includeItemTypes=Series&searchTerm=Zephyr%20Bay" -H "$A" | jq -r '.Items[0].Id')
MOVIE=$(curl -sf "$URL/Items?userId=$UID_&recursive=true&includeItemTypes=Movie&searchTerm=Seven%20Rivers" -H "$A" | jq -r '.Items[0].Id')
key() { for k in "$@"; do adb shell input keyevent "$k"; sleep 0.9; done; }
P="$OUT/perf-audit.txt"

round() { # level round-number
  local lvl=$1 n=$2
  adb shell am force-stop "$PKG"; sleep 2; adb logcat -c
  adb shell am start -n "$PKG/$ACT" --ez perfTrace true --ei perfLevel "$lvl" --es route section:Home >/dev/null
  sleep 16
  local L; L=$(adb logcat -d | tr -d '\r')
  echo "L$lvl #$n start: $(echo "$L" | grep -m1 -o 'Home ready in [0-9]* ms ([a-z ]*)') | $(echo "$L" | grep -m1 -o 'Home rows from the server in [0-9]* ms') | $(echo "$L" | grep -m1 -o 'Server requests in the first 10 s: .*')" | tee -a "$P"
  echo "L$lvl #$n start: $(echo "$L" | grep -m1 -o 'Main thread blocked .*')" | tee -a "$P"
  echo "$L" | grep -E "Perf *:   ~" | head -6 | sed -E "s/^.*Perf *: /L$lvl #$n   /" | tee -a "$P"
  # Details pages from Home's first poster row: open, wait, back; five titles.
  key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
  for i in 1 2 3 4 5; do
    key KEYCODE_DPAD_CENTER; sleep 3; key KEYCODE_BACK; sleep 1; key KEYCODE_DPAD_RIGHT
  done
  # The same titles again (pages opened before, data the app may already have).
  key KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT
  for i in 1 2 3; do
    key KEYCODE_DPAD_CENTER; sleep 3; key KEYCODE_BACK; sleep 1; key KEYCODE_DPAD_RIGHT
  done
  # A series with two seasons: open it, go down to the season tabs, switch to the other season,
  # back to the first, and to the other again.
  if [ -n "$SERIES" ] && [ "$SERIES" != null ]; then
    adb shell am start --activity-single-top -n "$PKG/$ACT" --es route "detail:$SERIES" >/dev/null; sleep 5
    key KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER; sleep 2
    key KEYCODE_DPAD_LEFT KEYCODE_DPAD_CENTER; sleep 2
    key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER; sleep 2
    key KEYCODE_BACK; sleep 2
  fi
  # Play a film from its page (resuming at 1:00), the usual way: the page prepares playback while open.
  if [ -n "$MOVIE" ] && [ "$MOVIE" != null ]; then
    curl -sf -X POST "$URL/UserItems/$MOVIE/UserData?userId=$UID_" -H "$A" -H "Content-Type: application/json" -d '{"PlaybackPositionTicks":600000000}' >/dev/null || true
    adb shell am start --activity-single-top -n "$PKG/$ACT" --es route "detail:$MOVIE" >/dev/null; sleep 5
    key KEYCODE_DPAD_CENTER; sleep 10; key KEYCODE_BACK; sleep 3; key KEYCODE_BACK; sleep 2
  fi
  L=$(adb logcat -d | tr -d '\r')
  echo "$L" | grep -oE "Detail loaded in [0-9]+ ms \([^)]*\)" | sed "s/^/L$lvl #$n /" | tee -a "$P"
  echo "$L" | grep -oE "Season shown in [0-9]+ ms \([^)]*\)" | sed "s/^/L$lvl #$n /" | tee -a "$P"
  echo "$L" | grep -oE "Prepared in [0-9]+ ms \([^)]*\)" | sed "s/^/L$lvl #$n /" | tee -a "$P"
  echo "$L" | grep -oE "Playing after [0-9]+ ms \([^)]*\)" | sed "s/^/L$lvl #$n /" | tee -a "$P"
  local api dup
  api=$(echo "$L" | grep -E "ApiTrace *:" | sed -E 's/^.*ApiTrace *: //')
  dup=$(echo "$api" | sort | uniq -c | awk '$1>1' | sort -rn)
  echo "L$lvl #$n API requests in the round: $(echo "$api" | grep -c .), repeated exactly: $(echo "$dup" | awk '{s+=$1-1} END{print s+0}')" | tee -a "$P"
  echo "$dup" | head -8 | sed -E "s/^ *([0-9]+) /L$lvl #$n   x\1 /" | cut -c1-220 | tee -a "$P"
  echo "L$lvl #$n memory: $(adb shell dumpsys meminfo "$PKG" | tr -d '\r' | grep -E 'TOTAL PSS|^ *TOTAL ' | head -1 | sed -E 's/ +/ /g')" | tee -a "$P"
  echo "$L" >> "$OUT/logcat-perf-audit.txt"
}

: > "$P"
# Level 1 = the previous build, level 2 = now (PerfTuning), alternating on the same emulator.
for n in 1 2 3; do round 1 $n; round 2 $n; done
if grep -qE "FATAL EXCEPTION" "$OUT/logcat-perf-audit.txt"; then
  echo "Crash during the performance rounds:"; grep -E -A25 "FATAL EXCEPTION" "$OUT/logcat-perf-audit.txt" | head -60; exit 1
fi
echo "Performance audit rounds done"
