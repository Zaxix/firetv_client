---
tags: [feature]
---
# Skip Markers

## What it does
Intro/recap/credits sections on the timeline and **Skip** buttons, plus "Next Episode" from the start
of the credits.

## How it works
Sources, best first; later ones only fill kinds the earlier ones lack:
1. Server media segments (`/MediaSegments/{id}`, e.g. from the Intro Skipper plugin).
2. Chapter names ("Opening", "Credits"…).
3. `SkipDetector`, on the device:
   - **Recurring chapters** across the season (same unnamed length ±1.5 s near start/end).
   - **AniSkip** for anime (by MyAnimeList id; optional, Settings → Playback).
   - **Matching audio** (`AudioFingerprint`) for any show: low-rate audio from the server (8-bit
     mono) is turned into band-shape codes and matched against a neighbouring episode to find the
     shared theme / credits music. Each detection window uses its own play session so the server's
     encode is never reused across windows.

## Relevant files
`playback/SkipDetector.kt`, `playback/AudioFingerprint.kt`, `profiles/ProfileStores.kt`
(SkipMarkerStore).

## Dependencies
[[Video Player]] · [[API Endpoints]]

## Data storage
`skip-markers-<profile>.json`: only *detected* markers for episodes being watched; deleted when the
episode is finished, pruned when untouched.

## Performance considerations
Audio detection asks the server for small low-bitrate audio windows (~4 KB/s), only for the episode
being watched, after playback has started. Online lookups use a client without the Jellyfin token.

## User interactions
Settings → Playback → *Skip intro*: button / automatic / off.

## Known limitations
Detection needs a neighbouring episode with the same music; markers can be off for unusual edits.

## Related notes
[[Playback Issues]] · [[Server Overview]]
