#!/usr/bin/env bash
# Launches the real desktop build against the CI Jellyfin server under Xvfb and captures screenshots.
# Usage: screenshots.sh <app-launcher> <out-dir> <token>
set -uo pipefail
APP="$1"; OUT="$2"; TOKEN="$3"
URL=http://localhost:8096
A="Authorization: MediaBrowser Client=\"Aurora CI\", Device=\"CI\", DeviceId=\"aurora-ci\", Version=\"1.0.0\", Token=\"$TOKEN\""
mkdir -p "$OUT"
CFG="$HOME/.config/aurora-tv"; mkdir -p "$CFG"

INFO=$(curl -sf "$URL/System/Info/Public")
SID=$(echo "$INFO" | jq -r .Id); VER=$(echo "$INFO" | jq -r .Version); NAME=$(echo "$INFO" | jq -r .ServerName)
ME=$(curl -sf "$URL/Users/Me" -H "$A"); UID_=$(echo "$ME" | jq -r .Id); UNAME=$(echo "$ME" | jq -r .Name)
q() { curl -sf "$URL/Items?userId=$UID_&recursive=true&$1" -H "$A" | jq -r '.Items[0].Id'; }
GLASS=$(q "includeItemTypes=Movie&searchTerm=Glass%20Meridian")
SERIES=$(q "includeItemTypes=Series")
EP1=$(q "includeItemTypes=Episode&sortBy=SortName")
SEVEN=$(q "includeItemTypes=Movie&searchTerm=Seven%20Rivers")

# Seed a signed-in session exactly as the app would store it (token only in the vault file).
cat > "$CFG/servers.json" <<JSON
{"deviceId":"aurora-ci-desktop","servers":[{"id":"$SID","name":"$NAME","url":"$URL","version":"$VER","users":[{"id":"$UID_","name":"$UNAME"}],"lastUserId":"$UID_"}],"lastServerId":"$SID"}
JSON
printf '{"token:%s:%s":"%s"}' "$SID" "$UID_" "$(printf %s "$TOKEN" | base64 -w0)" > "$CFG/vault.json"
echo '{"x":0.0,"y":0.0,"width":1920.0,"height":1080.0}' > "$CFG/window.json"

theme() { echo "{\"theme\":\"$1\",\"subtitleMode\":\"Always\",\"preferredSubtitleLanguage\":\"eng\"}" > "$CFG/settings.json"; }

shoot() { # name route seconds [keys...]
  local name=$1 route=$2 wait=$3; shift 3
  if [ -n "$route" ]; then "$APP" --route "$route" >"$OUT/$name.log" 2>&1 & else "$APP" >"$OUT/$name.log" 2>&1 & fi
  local pid=$!
  sleep "$wait"
  local win; win=$(xdotool search --name "Aurora TV" 2>/dev/null | head -1)
  [ -n "$win" ] && xdotool windowfocus "$win" 2>/dev/null
  for k in "$@"; do
    case "$k" in
      mouse:*) xy=${k#mouse:}; xdotool mousemove "${xy%,*}" "${xy#*,}"; sleep 0.3; xdotool mousemove_relative 4 0; sleep 1.2;;
      *) xdotool key --delay 150 "$k"; sleep 1.2;;
    esac
  done
  sleep 1
  import -window root "$OUT/$name.png"
  kill "$pid" 2>/dev/null; sleep 1; kill -9 "$pid" 2>/dev/null; wait "$pid" 2>/dev/null
  echo "captured $name"
}

theme Dark
shoot 01-home-dark "" 25
shoot 02-home-rails-dark "" 22 Down Right
shoot 03-movie-detail "detail:$GLASS" 15
shoot 04-series-detail "detail:$SERIES" 15 Down Down
shoot 05-movies-library "section:Movies" 15 Down
shoot 06-search "section:Search" 12
shoot 07-settings "section:Settings" 10 Down
shoot 08-about-capabilities "section:Settings" 12 Down Down Down Down
shoot 09-player-ass-typesetting "player:$EP1" 14
shoot 10-player-controls "player:$SEVEN" 12 Up
# Hovering the timeline with a mouse shows the time and the trickplay thumbnail at that point.
shoot 10b-player-scrub-preview "player:$SEVEN" 12 Up mouse:1150,978
theme Light
shoot 11-home-light "" 25
shoot 12-detail-light "detail:$SERIES" 15
theme System
shoot 13-settings-system-theme "section:Settings" 10

cp "$CFG/aurora.log" "$OUT/aurora.log" 2>/dev/null || true
ls -la "$OUT"
