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
TIDE=$(curl -sf "$URL/Items?userId=$UID_&recursive=true&includeItemTypes=Movie&searchTerm=Tidewater" -H "$A" | jq -r '.Items[0].Id')
SEVEN=$(curl -sf "$URL/Items?userId=$UID_&recursive=true&includeItemTypes=Movie&searchTerm=Seven%20Rivers" -H "$A" | jq -r '.Items[0].Id')

# The debug run left EP1 with a resume point inside its trailing (subtitle-only) section of the
# synthetic file; start it from the beginning so the release build is judged on real playback.
curl -sf -X POST "$URL/UserItems/$EP1/UserData?userId=$UID_" -H "$A" -H "Content-Type: application/json" -d '{"PlaybackPositionTicks":0}' >/dev/null || true

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

# ---- Performance (release build) ----
# Cold start to first frame (am start -W) and to Home's rows (the app logs "Perf: Home ready"),
# then frame timing while moving through Home's rows quickly, like holding the remote's arrows.
perf_round() {
  local label="$1"
  for i in 1 2 3; do
    adb shell am force-stop "$PKG"; sleep 2; adb logcat -c
    # Straight to Home (a link skips "Who's watching?"), so this measures the app, not a person picking.
    t=$(adb shell am start -W -n "$PKG/$ACT" --es route section:Home | tr -d '\r' | awk -F': ' '/TotalTime/{print $2}')
    sleep 15 # Home logs its request count 10 s after it opens
    p=$(adb logcat -d -s Perf | tr -d '\r')
    h=$(echo "$p" | grep -o 'Home ready in [0-9]* ms ([a-z ]*)' | head -1 | sed 's/Home ready in //')
    f=$(echo "$p" | grep -o 'Home rows from the server in [0-9]* ms' | head -1 | grep -o '[0-9]* ms')
    c=$(echo "$p" | grep -o 'Saved Home [a-z ]* in [0-9]* ms' | head -1 | sed 's/Saved Home //')
    d=$(echo "$p" | grep -o 'Saved Home: .*' | head -1)
    q=$(echo "$p" | grep -o 'Server requests in the first 10 s: .*' | head -1 | sed 's/Server requests in the first 10 s: //')
    echo "$label cold start #$i: first frame ${t:-?} ms, Home shown ${h:-?}, server rows ${f:-?}, requests ${q:-?}, saved copy ${c:-?} [${d:-}]" | tee -a "$OUT/perf.txt"
  done
  adb shell dumpsys gfxinfo "$PKG" reset >/dev/null
  adb shell 'for k in 20 20 22 22 22 22 22 22 22 22 22 22 20 22 22 22 22 22 22 22 19 21 21 21 21 21 21 21 21 21 21 19 19; do input keyevent $k; sleep 0.18; done'
  sleep 2
  adb shell dumpsys gfxinfo "$PKG" | tr -d '\r' | grep -E "Total frames rendered|Janky frames|50th percentile|90th percentile|95th percentile|99th percentile" | sed "s/^/$label scroll: /" | tee -a "$OUT/perf.txt"
}
: > "$OUT/perf.txt"
echo "dexopt: $(adb shell dumpsys package "$PKG" | tr -d '\r' | grep -m1 -o 'status=[a-z-]*\]\?' || true)" | tee -a "$OUT/perf.txt"
perf_round "as installed"
# What a TV does on its own once idle: compile the app with its startup profile.
adb shell cmd package compile -m speed-profile -f "$PKG" >/dev/null 2>&1 || true
perf_round "after profile compile"
# ---- Home opened again in the same session (side menu: Movies, then Home) ----
# How soon its rows are back and what that costs the server ("Perf: Home opened again").
adb shell am force-stop "$PKG"; sleep 2; adb logcat -c
adb shell am start -n "$PKG/$ACT" --es route section:Home >/dev/null; sleep 15
for i in 1 2 3; do
  adb shell am start --activity-single-top -n "$PKG/$ACT" --es route section:Movies >/dev/null; sleep 5
  adb shell am start --activity-single-top -n "$PKG/$ACT" --es route section:Home >/dev/null; sleep 13
done
adb logcat -d -s Perf | tr -d '\r' | grep -o 'Home opened again: .*' | sed 's/^/revisit: /' | tee -a "$OUT/perf.txt"
open() { adb shell am force-stop "$PKG"; sleep 1; adb shell am start -n "$PKG/$ACT" --es route "$1"; }
open "player:$EP1"; sleep 20; shot release-03-player-ass
adb shell 'input keyevent KEYCODE_DPAD_UP; sleep 0.6; input keyevent KEYCODE_DPAD_RIGHT; sleep 0.2; screencap -p /sdcard/scrub.png'
adb pull -q /sdcard/scrub.png "$OUT/release-04-scrub.png" >/dev/null && echo "captured release-04-scrub"
open "player:$GLASS"; sleep 20; shot release-05-player-hevc
# The shrunk build must also run libmpv (JNI callbacks need R8 keep rules).
curl -sf -X POST "$URL/UserItems/$GLASS/UserData?userId=$UID_" -H "$A" -H "Content-Type: application/json" -d '{"PlaybackPositionTicks":0}' >/dev/null || true
adb shell am force-stop "$PKG"; sleep 1; adb shell am start -n "$PKG/$ACT" --es player mpv --es route "player:$GLASS"; sleep 22; shot release-06-mpv-hevc


adb logcat -d > "$OUT/logcat-release.txt"
# The player must reach READY (actually playing) at least once.
grep -q "ExoEngine: state=ready" "$OUT/logcat-release.txt" || { echo "Release build never started playback"; FAIL=1; }
grep -q "MpvEngine: libmpv ready" "$OUT/logcat-release.txt" || { echo "Release build couldn't start libmpv"; FAIL=1; }
if grep -qE "FATAL EXCEPTION|UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError|SerializationException" "$OUT/logcat-release.txt"; then
  echo "Release build failed at runtime:"
  grep -E -A25 "FATAL EXCEPTION|UnsatisfiedLinkError|ClassNotFoundException|NoSuchMethodError|SerializationException" "$OUT/logcat-release.txt" | head -80
  exit 1
fi
grep -E "ExoEngine|MpvEngine|Negotiator|PlayerController|font attachments" "$OUT/logcat-release.txt" | tail -40 || true
# Server-side view of the transcodes (helps tell a client stall from a server failure).
docker exec jellyfin sh -c 'ls -t /config/log/FFmpeg.Transcode-*.log 2>/dev/null | head -3 | while read f; do echo "== $f"; head -c 600 "$f" | tr -d "\r" | grep -E "^/|-ss|ffmpeg" | sed -E "s/(api_key|ApiKey)=[^& ]+/\1=REDACTED/g" | head -3; tail -5 "$f"; done' > "$OUT/jellyfin-transcodes.txt" 2>&1 || true
cat "$OUT/jellyfin-transcodes.txt" | head -60
[ "${FAIL:-0}" = 1 ] && exit 1

# ---- Resume speed, before/after: Resume → picture moving, on a 3-minute 1080p file (~6 Mbps, a keyframe
# every 10 s, two audio tracks, ASS + embedded fonts), resuming at 2:37 (7 s past a keyframe). Each round
# then plays for ~20 s to check it stays smooth. Levels (ResumeTuning): 0 = original, 1 = previous
# build, 2 = now. "deeplink" opens the player cold; "detail" is the usual path: the details page
# prepares playback while it's open, then Select on Resume.
resume_round() { # level how item-id seconds label
  curl -sf -X POST "$URL/UserItems/$3/UserData?userId=$UID_" -H "$A" -H "Content-Type: application/json" -d "{\"PlaybackPositionTicks\":$(( $4 * 10000000 ))}" >/dev/null || true
  adb shell am force-stop "$PKG"; sleep 1; adb logcat -c
  if [ "$2" = detail ]; then
    adb shell am start -n "$PKG/$ACT" --es player exoplayer --ei resumeLevel "$1" --es route "detail:$3" >/dev/null; sleep 8
    adb shell input keyevent KEYCODE_DPAD_CENTER
  else
    adb shell am start -n "$PKG/$ACT" --es player exoplayer --ei resumeLevel "$1" --es route "player:$3" >/dev/null
  fi
  sleep 26
  L=$(adb logcat -d | tr -d '\r'); echo "$L" >> "$OUT/logcat-resume.txt"
  f() { echo "$L" | grep -m1 -E "$1" | sed -E 's/^.*(Perf|ExoEngine) *: //'; }
  local play eng prep kf pos rebuf
  play=$(f "Perf *: Playing after"); eng=$(f "Perf *: Engine start"); prep=$(f "Perf *: Prepared in"); kf=$(f "ExoEngine.*keyframe")
  pos=$(echo "$L" | grep -m1 "ExoEngine: state=ready" | sed -E 's/.*pos=([0-9]+).*/\1/')
  rebuf=$(echo "$L" | awk '/ExoEngine: state=ready/{r=1;next} r&&/ExoEngine: state=buffering/{n++} END{print n+0}')
  echo "L$1 $2 $5: ${play:-no playback logged} | first READY at ${pos:-?} ms (asked $(( $4 * 1000 ))) | stalls after start: $rebuf | ${eng:-} | ${prep:-} | ${kf:-}" | tee -a "$OUT/perf.txt"
}
echo "---- Resume: Tidewater at 2:37 (Direct Play) ----" | tee -a "$OUT/perf.txt"
for round in 1 2; do
  for lvl in 0 1 2; do resume_round $lvl deeplink "$TIDE" 157 "resume 2:37"; done
done
for round in 1 2; do
  for lvl in 0 2; do resume_round $lvl detail "$TIDE" 157 "resume 2:37"; done
done
resume_round 2 deeplink "$TIDE" 0 "from the start (for comparison)"
echo "---- Resume: Glass Meridian at 0:17 (server transcode) ----" | tee -a "$OUT/perf.txt"
for lvl in 0 2; do resume_round $lvl deeplink "$GLASS" 17 "resume 0:17"; done
if grep -qE "FATAL EXCEPTION" "$OUT/logcat-resume.txt"; then
  echo "Crash during the resume rounds:"; grep -E -A25 "FATAL EXCEPTION" "$OUT/logcat-resume.txt" | head -60; exit 1
fi
echo "Release smoke test passed"
