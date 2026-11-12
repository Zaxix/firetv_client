#!/usr/bin/env bash
# Completes the Jellyfin first-run wizard, creates Movies + TV libraries over /media and waits for the scan.
# Prints the access token for the CI user on the last line.
set -euo pipefail
URL=${JF_URL:-http://localhost:8096}
AUTH='MediaBrowser Client="Aurora CI", Device="CI", DeviceId="aurora-ci", Version="1.0.0"'
J='Content-Type: application/json'

for i in $(seq 1 90); do curl -sf "$URL/System/Info/Public" >/dev/null && break; sleep 2; done
curl -sf "$URL/System/Info/Public" | jq -c '{ServerName, Version, StartupWizardCompleted}' >&2

if [ "$(curl -sf "$URL/System/Info/Public" | jq -r .StartupWizardCompleted)" != "true" ]; then
  curl -sf -X POST "$URL/Startup/Configuration" -H "$J" -d '{"UICulture":"en-US","MetadataCountryCode":"US","PreferredMetadataLanguage":"en"}'
  for i in $(seq 1 20); do curl -sf "$URL/Startup/User" >/dev/null && break; sleep 1; done
  curl -sf -X POST "$URL/Startup/User" -H "$J" -d '{"Name":"aurora","Password":"aurora-ci"}'
  curl -sf -X POST "$URL/Startup/RemoteAccess" -H "$J" -d '{"EnableRemoteAccess":true,"EnableAutomaticPortMapping":false}' || true
  curl -sf -X POST "$URL/Startup/Complete"
fi

TOKEN=$(curl -sf -X POST "$URL/Users/AuthenticateByName" -H "$J" -H "Authorization: $AUTH" -d '{"Username":"aurora","Pw":"aurora-ci"}' | jq -r .AccessToken)
A="Authorization: $AUTH, Token=\"$TOKEN\""

opts='{"LibraryOptions":{"EnableRealtimeMonitor":false,"EnableChapterImageExtraction":false,"EnableTrickplayImageExtraction":false,"SaveLocalMetadata":false}}'
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
curl -sf "$URL/Items?userId=$USER_ID&recursive=true&includeItemTypes=Movie,Series,Episode&fields=MediaStreams" -H "$A" \
  | jq -r '.Items[] | "\(.Type)\t\(.Name)\t\(.ProductionYear)\timages=\(.ImageTags|keys|join(","))"' >&2
echo "$TOKEN"
