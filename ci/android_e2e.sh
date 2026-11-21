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
: > "$OUT/navigation.txt"
UID_=$(curl -sf "$URL/Users/Me" -H "$A" | jq -r .Id)
q() { curl -sf "$URL/Items?userId=$UID_&recursive=true&$1" -H "$A" | jq -r '.Items[0].Id'; }
GLASS=$(q "includeItemTypes=Movie&searchTerm=Glass%20Meridian")
SERIES=$(q "includeItemTypes=Series&sortBy=SortName&searchTerm=Skylark")
EP1=$(q "includeItemTypes=Episode&sortBy=SortName")
EP3=$(curl -sf "$URL/Items?userId=$UID_&recursive=true&includeItemTypes=Episode&sortBy=SortName" -H "$A" | jq -r '.Items[2].Id')
SEVEN=$(q "includeItemTypes=Movie&searchTerm=Seven%20Rivers")
WAVES=$(q "includeItemTypes=Series&searchTerm=Wavelength")
LANTERN=$(q "includeItemTypes=Movie&searchTerm=Lantern")
OWL=$(q "includeItemTypes=Movie&searchTerm=Owl%20Post")
WAVE2=$(curl -sf "$URL/Shows/$WAVES/Episodes?userId=$UID_" -H "$A" | jq -r '.Items[1].Id')
PAPER=$(q "includeItemTypes=Series&searchTerm=Paper%20Town")
PAPER12=$(curl -sf "$URL/Shows/$PAPER/Episodes?userId=$UID_" -H "$A" | jq -r '.Items[0].Id')
BAY=$(q "includeItemTypes=Series&searchTerm=Zephyr%20Bay")

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
# Left from the first card opens the sidebar (Back on Home now asks before exiting instead).
key KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT; sleep 2; shot android-07-back-to-sidebar
# Sidebar: Down to "Up Next", Select switches section and hands focus to its content.
key KEYCODE_DPAD_DOWN; sleep 1; shot android-07b-sidebar-down
key KEYCODE_DPAD_CENTER; sleep 6; shot android-07c-sidebar-select

# Profiles: a plain launch asks who's watching; OK opens the highlighted (last used) profile.
adb shell am force-stop "$PKG"; sleep 1; adb shell am start -n "$PKG/$ACT"; sleep 10; shot android-24-profiles
key KEYCODE_DPAD_CENTER; sleep 8; shot android-25-profile-opened
# The profile button at the top right (Up from the banner) opens the same screen; Back returns.
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP; sleep 1; shot android-26-profile-button
key KEYCODE_DPAD_CENTER; sleep 3; shot android-27-profiles-from-app
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER; sleep 2; shot android-28-profiles-manage
key KEYCODE_BACK; sleep 1; key KEYCODE_BACK; sleep 4; shot android-29-profiles-back
# Search: typing with typos still finds the title, as you type.
adb shell am force-stop "$PKG"; sleep 1; adb shell am start -n "$PKG/$ACT" --es route "section:Search"; sleep 8; adb shell input text "glas%smeridan"; sleep 5; shot android-30-search-typo
key KEYCODE_DPAD_DOWN; sleep 1; shot android-31-search-results

# Deep links (session is restored from the Keystore vault on relaunch).
open() { adb shell am force-stop "$PKG"; sleep 1; adb shell am start -n "$PKG/$ACT" --es route "$1"; }
# Turn subtitles on (English, always) the same way the Settings screen persists them.
adb shell am force-stop "$PKG"
# Settings belong to the profile now (files/settings-<profile>.json); write both.
echo '{"theme":"Dark","subtitleMode":"Always","preferredSubtitleLanguage":"eng"}' | adb shell "run-as $PKG sh -c 'cat > files/settings.json; for f in files/settings-*.json; do cp files/settings.json \$f; done'"
adb shell "run-as $PKG ls files" || true
adb shell "run-as $PKG cat files/settings.json" || true
open "detail:$SERIES"; sleep 14; key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; shot android-08-series-detail
# Back up to the buttons: the whole title and description must be on screen again.
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP; sleep 2; shot android-08b-detail-back-to-top
open "player:$EP1"; sleep 22; shot android-09-player-ass-typesetting
sleep 4; shot android-10-player-ass-later
key KEYCODE_DPAD_UP; sleep 2; shot android-11-player-controls
key KEYCODE_BACK; sleep 1
open "player:$EP3"; sleep 9; key KEYCODE_DPAD_DOWN; sleep 3; shot android-12-player-episode-browser
key KEYCODE_DPAD_RIGHT; sleep 1; shot android-13-player-episode-focus
# Up inside the menu stays in the menu (seasons row, or nowhere), never the player behind it.
key KEYCODE_DPAD_UP; sleep 1; shot android-13c-player-episode-menu-up
# Scrubbing: run the key presses and the capture inside the device in one shell, because each
# separate `adb shell input` takes seconds on a loaded emulator and the controls would time out.
open "player:$SEVEN"; sleep 9
adb shell 'input keyevent KEYCODE_DPAD_UP; sleep 0.3; input keyevent KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT; sleep 0.2; screencap -p /sdcard/scrub.png'
adb pull -q /sdcard/scrub.png "$OUT/android-16-player-scrubbing.png" >/dev/null && echo "captured android-16-player-scrubbing"
# Detail page → Play with the remote: playback is prepared while the page is open ("Ready to play in … ms").
open "detail:$SEVEN"; sleep 6; key KEYCODE_DPAD_CENTER; sleep 8; shot android-13b-play-from-detail; key KEYCODE_BACK; sleep 2
open "player:$GLASS"; sleep 22; shot android-14-player-hevc-main10-ass
# A non-anime show with no chapters and no server markers: the intro is found by listening
# (theme shared with episode 1). 16 s in, the Skip Intro button should be up (intro is 8–38 s).
if [ -n "$WAVE2" ] && [ "$WAVE2" != null ]; then
  open "player:$WAVE2"; sleep 16; shot android-32-skip-intro-by-listening
  if adb logcat -d -s SkipDetector:I | grep -q "via matching audio"; then
    echo "✅ Intro/credits found by listening: $(adb logcat -d -s SkipDetector:I | tr -d '\r' | grep 'via matching audio' | tail -1 | sed -E 's/^.*SkipDetector *: //')" | tee -a "$OUT/navigation.txt"
  else
    echo "❌ Intro/credits by listening: nothing detected" | tee -a "$OUT/navigation.txt"; adb logcat -d -s SkipDetector:* JellyfinApi:* | tail -10; NAVFAIL=1
  fi
  key KEYCODE_BACK; sleep 2
fi
# A film of the Harry Potter collection (by its TMDB collection id): normal subtitles in the film font.
if [ -n "$OWL" ] && [ "$OWL" != null ]; then
  adb logcat -c
  open "player:$OWL"; sleep 12; shot android-33-film-subtitle-font
  if adb logcat -d -s Player:I | grep -q "Subtitles in IM Fell English"; then
    echo "✅ Film subtitle font: $(adb logcat -d -s Player:I | tr -d '\r' | grep -o 'Subtitles in .*' | tail -1)" | tee -a "$OUT/navigation.txt"
  else
    echo "❌ Film subtitle font not used for a film in TMDB collection 1241 (provider ids: $(curl -sf "$URL/Items/$OWL?userId=$UID_" -H "$A" | jq -c .ProviderIds))" | tee -a "$OUT/navigation.txt"; NAVFAIL=1
  fi
  key KEYCODE_BACK; sleep 2
fi
# Two episodes in one file: the second plays from 4:00 of the file, on its own 0:00–4:00 timeline.
# (The app splits the file the first time it lists the season; the series page does that first.)
if [ -n "$PAPER12" ] && [ "$PAPER12" != null ]; then
  open "detail:$PAPER"; sleep 6
  adb logcat -c
  open "player:vep~$PAPER12~1"; sleep 14; key KEYCODE_DPAD_CENTER; sleep 2; shot android-34-virtual-episode-2
  start=$(adb logcat -d -s Player:I | tr -d '\r' | grep -oE "Virtual episode: file [0-9]+.[0-9]+ ms, starting at [0-9]+" | head -1 | grep -oE "[0-9]+$")
  if [ -n "$start" ] && [ "$start" -ge 239000 ] && [ "$start" -le 250000 ]; then
    echo "✅ Virtual episode 2 starts inside its file at ${start} ms (its 0:00; true 240000)" | tee -a "$OUT/navigation.txt"
  else
    echo "❌ Virtual episode 2: engine started at '${start}' ms, expected about 240000" | tee -a "$OUT/navigation.txt"; adb logcat -d -s Player:* Splits:* ExoEngine:* | tail -15; NAVFAIL=1
  fi
  key KEYCODE_BACK; sleep 2; key KEYCODE_BACK; sleep 2
fi
# Episode order by remote: in the show's Episode Order screen choose Custom, pick up S02E02 (the
# server names it after its file), move it up twice (past S02E01, into season 1), put it down and save; the show then lists
# season 1 with four episodes. Back then asks whether to save, and Save is chosen.
if [ -n "$BAY" ] && [ "$BAY" != null ]; then
  adb logcat -c
  open "order:$BAY"; sleep 8; shot android-35-episode-order
  key KEYCODE_DPAD_UP KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER; sleep 2; shot android-36-episode-order-custom
  key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
  key KEYCODE_DPAD_CENTER KEYCODE_DPAD_UP KEYCODE_DPAD_UP; shot android-37-episode-order-moving; key KEYCODE_DPAD_CENTER; sleep 1
  moved=$(adb logcat -d -s EpisodeOrder:I | tr -d '\r' | grep -o 'Moved .*' | tail -1)
  if echo "$moved" | grep -q 'S02E02" to Season 1, place 4'; then
    echo "✅ Episode moved across seasons by remote: $moved" | tee -a "$OUT/navigation.txt"
  else
    echo "❌ Episode order by remote: expected S02E02 at Season 1 place 4, got '${moved}'" | tee -a "$OUT/navigation.txt"; adb logcat -d -s EpisodeOrder:* AuroraFocus:* | tail -10; NAVFAIL=1
  fi
  # Back with unsaved changes asks whether to save them (Save is focused).
  key KEYCODE_BACK; sleep 2; shot android-38-episode-order-save; key KEYCODE_DPAD_CENTER; sleep 3
  open "detail:$BAY"; sleep 8; shot android-39-episode-order-series
  if adb logcat -d -s EpisodeOrder:I | tr -d '\r' | grep -q "order saved" && adb logcat -d -s EpisodeOrder:I | tr -d '\r' | grep -q "in Custom order: Season 1 = 4 episodes"; then
    echo "✅ Custom episode order saved and used by the show's page: $(adb logcat -d -s EpisodeOrder:I | tr -d '\r' | grep -o 'in Custom order: .*' | tail -1)" | tee -a "$OUT/navigation.txt"
  else
    echo "❌ Custom episode order not saved or not used: $(adb logcat -d -s EpisodeOrder:I | tr -d '\r' | tail -4 | tr '\n' ' ')" | tee -a "$OUT/navigation.txt"; NAVFAIL=1
  fi
  # Leave the sample show in Jellyfin's order for anything after this.
  open "order:$BAY"; sleep 6; key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER; sleep 2
  key KEYCODE_BACK; sleep 2; key KEYCODE_BACK; sleep 2
fi
# Anime-style release (38 MB of fonts ahead of the video, subtitles almost always on screen):
# resume at 1:30, then skip back and forward with the bar. Each must end up playing, not stuck.
#   level 3 detail  : the usual path; the details page prepares playback and fetches the file's start
#   level 3 deeplink: cold start, the file's start already kept on the device
#   level 2 deeplink: the previous build (fonts downloaded on every start), for comparison
lantern_round() { # level how
  curl -sf -X POST "$URL/UserItems/$LANTERN/UserData?userId=$UID_" -H "$A" -H "Content-Type: application/json" -d '{"PlaybackPositionTicks":900000000}' >/dev/null || true
  adb shell am force-stop "$PKG"; sleep 1; adb shell log -t AuroraCI "lantern-$1-$2"
  if [ "$2" = detail ]; then
    adb shell am start -n "$PKG/$ACT" --ei resumeLevel "$1" --es route "detail:$LANTERN" >/dev/null; sleep 14
    adb shell input keyevent KEYCODE_DPAD_CENTER
  else
    adb shell am start -n "$PKG/$ACT" --ei resumeLevel "$1" --es route "player:$LANTERN" >/dev/null
  fi
  sleep 20
  local L; L=$(adb logcat -d | tr -d '\r' | sed -n "/AuroraCI.*lantern-$1-$2\$/,\$p")
  local resumed; resumed=$(echo "$L" | grep -m1 -E "Perf *: Playing after" | sed -E 's/^.*Perf *: //')
  local engine; engine=$(echo "$L" | grep -m1 -E "Perf *: Engine start" | sed -E 's/^.*Perf *: //')
  # Skip back three steps on the bar and commit; then forward three.
  adb shell 'input keyevent KEYCODE_DPAD_UP; sleep 0.6; input keyevent KEYCODE_DPAD_LEFT; sleep 0.2; input keyevent KEYCODE_DPAD_LEFT; sleep 0.2; input keyevent KEYCODE_DPAD_LEFT; sleep 0.3; input keyevent KEYCODE_DPAD_CENTER'
  sleep 15
  adb shell 'input keyevent KEYCODE_DPAD_UP; sleep 0.6; input keyevent KEYCODE_DPAD_RIGHT; sleep 0.2; input keyevent KEYCODE_DPAD_RIGHT; sleep 0.2; input keyevent KEYCODE_DPAD_RIGHT; sleep 0.3; input keyevent KEYCODE_DPAD_CENTER'
  sleep 15; shot "android-33-anime-resume-seek-L$1-$2"
  L=$(adb logcat -d | tr -d '\r' | sed -n "/AuroraCI.*lantern-$1-$2\$/,\$p")
  local states; states=$(echo "$L" | grep -E "ExoEngine: state=" | sed -E 's/^.*state=([a-z]+) pos=([0-9]+).*/\1@\2/' | tr '\n' ' ')
  local last; last=$(echo "$L" | grep -E "ExoEngine: state=" | tail -1 | sed -E 's/^.*state=([a-z]+).*/\1/')
  local seeks; seeks=$(echo "$L" | grep -cE "ExoEngine: state=ready")
  local stuck; stuck=$(echo "$L" | grep -cE "Stuck buffering|Still stuck")
  echo "$L" | grep -E "Stuck buffering|Still stuck|Stopped loading while buffering|HeaderCache" | sed -E 's/^.*(ExoEngine|HeaderCache) *: /    /' | head -6
  if [ "$last" = ready ] && [ -n "$resumed" ] && [ "$seeks" -ge 3 ]; then
    echo "✅ Anime resume + 2 seeks (level $1, $2): ${resumed} | ${engine} | states: $states| watchdog: $stuck" | tee -a "$OUT/navigation.txt"
  elif [ "$1" -lt 3 ] && [ -n "$resumed" ]; then
    # Older levels run only to compare start-up times; their seeks share the timing of the current
    # build's, and a slower start can let the second seek land past the end of the 3-minute file.
    echo "⚠️ Anime resume (level $1, $2, comparison only): ${resumed} | ${engine} | states: $states| watchdog: $stuck" | tee -a "$OUT/navigation.txt"
  else
    echo "❌ Anime resume + 2 seeks (level $1, $2): last state ${last:-?}, ready ${seeks}x, ${resumed:-never played} | ${engine} | states: $states| watchdog: $stuck" | tee -a "$OUT/navigation.txt"; NAVFAIL=1
  fi
}
if [ -n "$LANTERN" ] && [ "$LANTERN" != null ]; then lantern_round 3 detail; lantern_round 3 deeplink; lantern_round 2 deeplink; adb shell am force-stop "$PKG"; fi
open "brand:netflix"; sleep 10; shot android-17-brand-hub
# Home: scroll to the last row (titles under the cards must stay on screen), then back up to the banner.
open "section:Home"; sleep 12; key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; sleep 2; shot android-22-home-bottom
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP; sleep 2; shot android-23-home-back-to-top
# ---- Directional focus with the remote: Right out of the sidebar, Right at the end of rows, and the
# profile button only through Up from the top. Each step checks which region has focus (the app logs
# "AuroraFocus: region=…") and takes a screenshot.
region() { adb logcat -d -s AuroraFocus:I | tr -d '\r' | grep -o 'region=[a-z]*' | tail -1 | cut -d= -f2; }
expect() { # step expected-region
  sleep 1; local got; got=$(region)
  if [ "$got" = "$2" ]; then echo "✅ $1: focus in $got" | tee -a "$OUT/navigation.txt"
  else echo "❌ $1: expected $2, focus in ${got:-?}" | tee -a "$OUT/navigation.txt"; NAVFAIL=1; fi
}
open "section:Home"; sleep 14
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; expect "Down onto a row" content; shot nav-01-on-a-row
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT; key KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT; expect "Left from the first card opens the sidebar" sidebar; shot nav-02-sidebar-open
key KEYCODE_DPAD_RIGHT; expect "Right in the sidebar closes it (like Back)" content; shot nav-03-sidebar-right-closes
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
expect "Right past the end of a row stays on the last card" content; shot nav-04-row-end
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP; expect "Up from the top of Home reaches the profile button" header; shot nav-05-up-to-profile
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_LEFT KEYCODE_DPAD_UP; expect "Left/Right/Up on the profile button go nowhere" header
key KEYCODE_DPAD_DOWN; expect "Down from the profile button returns to the screen" content; shot nav-06-profile-down
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP; key KEYCODE_BACK; expect "Back on the profile button returns to the screen" content
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; sleep 2; shot nav-07-home-recommendations
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
expect "Right past the end of a recommendation row" content
# Search: results arrive while typing; the end of a result row and empty results must not escape.
open "section:Search"; sleep 8; adb shell input text "thriller"; sleep 5
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
expect "Right past the end of a search result row" content; shot nav-08-search-row-end
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP; expect "Up from the search box reaches the profile button" header
key KEYCODE_DPAD_DOWN; expect "Down returns to search" content
open "section:Search"; sleep 8; adb shell input text "zzqxv"; sleep 4
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT; expect "Keys on empty search results stay on the screen" content; shot nav-09-search-empty
# A single-title row (detail page cast/seasons rows vary): Right at the only item stays.
open "detail:$SERIES"; sleep 12; key KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT; expect "Right at the end of a short row on a details page" content; shot nav-10-detail-row-end
adb logcat -d -s Recommend:I | tr -d '\r' | tail -5 | tee -a "$OUT/navigation.txt"
# ---- Layout and UI sounds (app started with --ez traceUi true: it logs sounds and a few positions) ----
opentrace() { adb shell am force-stop "$PKG"; sleep 1; adb shell am start -n "$PKG/$ACT" --ez traceUi true --es route "$1" >/dev/null; }
lastlog() { adb logcat -d -s "$1":I | tr -d '\r' | grep -E "$2" | tail -1 | sed -E "s/^.*$1 *: //"; }
sounds() { adb logcat -d -s UiSound:I | tr -d '\r' | grep -cE "UiSound *: $1"; }
check() { # name ok-flag detail
  if [ "$2" = 1 ]; then echo "✅ $1: $3" | tee -a "$OUT/navigation.txt"; else echo "❌ $1: $3" | tee -a "$OUT/navigation.txt"; NAVFAIL=1; fi
}
opentrace "section:Home"; sleep 14
# 1. Right to the end of a row and back: the first card must return to the row's start, clear of the sidebar.
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT KEYCODE_DPAD_RIGHT
key KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT
[ "$(region)" = sidebar ] && key KEYCODE_DPAD_RIGHT
sleep 1.5; shot layout-01-row-back-to-first
fc=$(lastlog AuroraLayout "first card x="); x=$(echo "$fc" | sed -E 's/.*x=(-?[0-9]+).*/\1/'); st=$(echo "$fc" | sed -E 's/.*rowStart=([0-9]+).*/\1/')
ok=0; [ -n "$x" ] && [ -n "$st" ] && [ $(( x > st ? x - st : st - x )) -le 4 ] && ok=1
check "First card back at the row's start after going to the end and back" $ok "${fc:-no position logged}"
# 2. Down through Home and back up: the focused row's heading must be on screen.
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP; sleep 1.5; shot layout-02-row-heading-after-up
rv=$(lastlog AuroraLayout "revealed top="); top=$(echo "$rv" | sed -E 's/.*top=(-?[0-9]+).*/\1/')
ok=0; [ -n "$top" ] && [ "$top" -ge 0 ] && ok=1
check "Row heading visible when moving up through Home" $ok "${rv:-nothing logged} (row top must be ≥ 0)"
# 3. Sounds: three moves in the sidebar = three ticks; OK = one chime.
key KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT KEYCODE_DPAD_LEFT; sleep 1
m0=$(sounds Move); key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; sleep 1; m1=$(sounds Move)
s0=$(sounds Select); key KEYCODE_DPAD_CENTER; sleep 2; s1=$(sounds Select)
ok=0; [ "$((m1 - m0))" = 3 ] && [ "$((s1 - s0))" = 1 ] && ok=1
check "One move sound per key press, one chime on OK" $ok "moves +$((m1 - m0)) for 3 presses, chimes +$((s1 - s0)) for 1 press (sidebar: $(region))"
# 4. Details page: down through the rows and back up: the whole hero returns; episode descriptions show.
opentrace "detail:$SERIES"; sleep 14
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; sleep 2; shot layout-03-episode-description
key KEYCODE_DPAD_RIGHT; sleep 1.5; shot layout-04-episode-description-next
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_UP; sleep 2; shot layout-05-detail-hero-after-up
hero=$(lastlog AuroraLayout "detail hero at"); ok=0; echo "$hero" | grep -q "item=0 offset=0" && ok=1
check "Details page hero fully back after going down and up" $ok "${hero:-nothing logged}"
# 5. No sounds over a playing video.
opentrace "player:$SEVEN"; sleep 10; m0=$(sounds "(Move|Select)")
key KEYCODE_DPAD_UP KEYCODE_DPAD_RIGHT KEYCODE_DPAD_LEFT KEYCODE_DPAD_DOWN; sleep 1; m1=$(sounds "(Move|Select)")
ok=0; [ "$m1" = "$m0" ] && ok=1
check "No UI sounds while a video plays" $ok "+$((m1 - m0)) sounds for 4 presses in the player"
# 6. Switched off in Settings: silence.
adb shell am force-stop "$PKG"
echo '{"theme":"Dark","subtitleMode":"Always","preferredSubtitleLanguage":"eng","navigationSounds":false}' | adb shell "run-as $PKG sh -c 'cat > files/settings.json; for f in files/settings-*.json; do cp files/settings.json \$f; done'"
opentrace "section:Home"; sleep 12; m0=$(sounds "(Move|Select)")
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_RIGHT; sleep 1; m1=$(sounds "(Move|Select)")
ok=0; [ "$m1" = "$m0" ] && ok=1
check "Navigation sounds off in Settings means silence" $ok "+$((m1 - m0)) sounds for 3 presses"
adb shell am force-stop "$PKG"
echo '{"theme":"Dark","subtitleMode":"Always","preferredSubtitleLanguage":"eng"}' | adb shell "run-as $PKG sh -c 'cat > files/settings.json; for f in files/settings-*.json; do cp files/settings.json \$f; done'"

open "section:Settings"; sleep 10; key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; sleep 2; shot android-15a-settings-ratings
key KEYCODE_DPAD_DOWN; sleep 2; shot android-15-about-capabilities

# Top 10 in Your Home: the titles played above, ranked by time watched, as a row of posters with
# their places drawn as numerals behind them.
adb logcat -c
open "section:Home"; sleep 12; key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN; sleep 3; shot android-40-home-top10-a
key KEYCODE_DPAD_DOWN; sleep 3; shot android-41-home-top10-b
top=$(adb logcat -d -s Top10:I | tr -d '\r' | grep -o 'Top 10 in Your Home: .*' | tail -1)
if echo "$top" | grep -qE ': ([3-9]|10) titles shown'; then
  echo "✅ $top" | tee -a "$OUT/navigation.txt"
else
  echo "❌ Top 10 row: ${top:-not worked out} $(adb logcat -d -s Top10:* | tr -d '\r' | tail -3 | tr '\n' ' ')" | tee -a "$OUT/navigation.txt"; NAVFAIL=1
fi

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
echo "---- Time from Play to the picture moving ----"; grep -E "Perf *: Playing after" "$OUT/logcat-full.txt" | sed -E "s/^.*Playing/Playing/" || true
echo "---- Skip markers: time from Play to ready ----"; grep -E "Perf *: Skip markers|SkipDetector" "$OUT/logcat-full.txt" | sed -E "s/^.*(Perf|SkipDetector) *: //" || true
echo "---- Aurora log excerpt ----"; grep -vE "AuroraKeys|AuroraFocus" "$OUT/logcat-aurora.txt" | tail -40
if grep -q "FATAL EXCEPTION" "$OUT/logcat-full.txt"; then echo "App crashed"; grep -A30 "FATAL EXCEPTION" "$OUT/logcat-full.txt" | head -60; exit 1; fi
if [ "${NAVFAIL:-0}" = 1 ]; then echo "Navigation checks failed:"; cat "$OUT/navigation.txt"; exit 1; fi
