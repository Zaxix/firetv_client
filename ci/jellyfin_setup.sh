#!/usr/bin/env bash
# Completes the Jellyfin first-run wizard, creates Movies + TV libraries over /media and waits for the scan.
# Prints the access token for the CI user on the last line.
set -euo pipefail
URL=${JF_URL:-http://localhost:8096}
AUTH='MediaBrowser Client="Aurora CI", Device="CI", DeviceId="aurora-ci", Version="1.0.0"'
J='Content-Type: application/json'

# Wait until the server reports a version (during startup it answers with a placeholder).
for i in $(seq 1 120); do
  V=$(curl -sf "$URL/System/Info/Public" 2>/dev/null | jq -r '.Version // empty' 2>/dev/null || true)
  [ -n "$V" ] && break
  sleep 2
done
echo "Jellyfin public info: $(curl -sf "$URL/System/Info/Public")" >&2
retry() { for n in 1 2 3 4 5 6 7 8 9 10; do "$@" && return 0; echo "retry $n: $*" >&2; sleep 3; done; echo "FAILED: $*" >&2; return 1; }

if [ "$(curl -sf "$URL/System/Info/Public" | jq -r .StartupWizardCompleted)" != "true" ]; then
  echo "Running startup wizard" >&2
  retry curl -sSf -X POST "$URL/Startup/Configuration" -H "$J" -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}'
  retry curl -sSf "$URL/Startup/User" -o /dev/null
  retry curl -sSf -X POST "$URL/Startup/User" -H "$J" -d '{"Name":"aurora","Password":"aurora-ci"}'
  curl -sS -X POST "$URL/Startup/RemoteAccess" -H "$J" -d '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}' >&2 || true
  retry curl -sSf -X POST "$URL/Startup/Complete"
  echo "Wizard complete" >&2
fi

TOKEN=""
for n in $(seq 1 10); do
  TOKEN=$(curl -sf -X POST "$URL/Users/AuthenticateByName" -H "$J" -H "Authorization: $AUTH" -d '{"Username":"aurora","Pw":"aurora-ci"}' | jq -r '.AccessToken // empty' || true)
  [ -n "$TOKEN" ] && break; sleep 3
done
[ -n "$TOKEN" ] || { echo "Could not authenticate CI user" >&2; exit 1; }
A="Authorization: $AUTH, Token=\"$TOKEN\""

# The CI clips are 6-30 s long; Jellyfin only stores resume points for items longer than
# MinResumeDurationSeconds (default 300 s), so lower it to exercise resume/Continue Watching.
curl -sf "$URL/System/Configuration" -H "$A" | jq '.MinResumeDurationSeconds = 0 | .MinResumePct = 1 | .MaxResumePct = 95' > /tmp/jf_config.json
curl -sf -X POST "$URL/System/Configuration" -H "$A" -H "$J" -d @/tmp/jf_config.json && echo "Resume thresholds lowered for short test clips" >&2

# Local NFO + images only: no internet metadata or image providers, so nothing third-party appears in screenshots.
NOFETCH='{"MetadataFetchers":[],"ImageFetchers":[],"MetadataFetcherOrder":[],"ImageFetcherOrder":[]}'
TYPES=$(for t in Movie Series Season Episode Person; do echo "$NOFETCH" | jq -c --arg t "$t" '. + {Type:$t}'; done | jq -sc .)
opts=$(jq -nc --argjson types "$TYPES" '{LibraryOptions:{EnableRealtimeMonitor:false,EnableChapterImageExtraction:false,EnableTrickplayImageExtraction:true,SaveLocalMetadata:false,EnableInternetProviders:false,TypeOptions:$types}}')
curl -sf -X POST "$URL/Library/VirtualFolders?name=Movies&collectionType=movies&paths=%2Fmedia%2Fmovies&refreshLibrary=false" -H "$A" -H "$J" -d "$opts"
curl -sf -X POST "$URL/Library/VirtualFolders?name=TV%20Shows&collectionType=tvshows&paths=%2Fmedia%2Ftv&refreshLibrary=false" -H "$A" -H "$J" -d "$opts"
curl -sf -X POST "$URL/Library/Refresh" -H "$A"

USER_ID=$(curl -sf "$URL/Users/Me" -H "$A" | jq -r .Id)
for i in $(seq 1 120); do
  M=$(curl -sf "$URL/Items?userId=$USER_ID&recursive=true&includeItemTypes=Movie" -H "$A" | jq .TotalRecordCount)
  E=$(curl -sf "$URL/Items?userId=$USER_ID&recursive=true&includeItemTypes=Episode" -H "$A" | jq .TotalRecordCount)
  echo "scan: movies=$M episodes=$E" >&2
  [ "$M" -ge 5 ] && [ "$E" -ge 3 ] && break
  sleep 3
done
sleep 10 # let image/metadata providers settle
# Generate trickplay (scrub preview) sheets so the player's thumbnail previews are exercised.
TP=$(curl -sf "$URL/ScheduledTasks" -H "$A" | jq -r '.[] | select(.Key=="RefreshTrickplayImages") | .Id')
if [ -n "$TP" ]; then
  curl -sf -X POST "$URL/ScheduledTasks/Running/$TP" -H "$A" || true
  for i in $(seq 1 60); do
    sleep 3
    ST=$(curl -sf "$URL/ScheduledTasks/$TP" -H "$A" | jq -r '.State + " " + (.LastExecutionResult.Status // "-")')
    echo "trickplay: $ST" >&2
    case "$ST" in Idle\ Completed*|Idle\ Failed*) break;; esac
  done
fi
curl -sf "$URL/Items?userId=$USER_ID&recursive=true&includeItemTypes=Movie,Series,Episode&fields=MediaStreams,Trickplay" -H "$A" \
  | jq -r '.Items[] | "\(.Type)\t\(.Name)\t\(.ProductionYear)\timages=\(.ImageTags|keys|join(","))\ttrickplay=\((.Trickplay // {}) | [.[] | keys[]] | join(","))"' >&2
echo "$TOKEN"
