---
tags: [overview]
---
# App Overview

**Sharkfin** (package `com.auroratv.android`; the code still uses the earlier working name *Aurora*
in package and class names) is a client for a self-hosted **Jellyfin** media server. It shows the
server's movies and TV shows in a TV-style interface and plays them with native, hardware-accelerated
players.

## Platforms
| Platform | Shell | Player | Notes |
|---|---|---|---|
| Fire TV / Android TV (Android 6+, API 23) | `androidApp` | Media3 ExoPlayer (default) or libmpv | D-pad first; Fire TV is the main target |

The Windows app was removed; see [[Supported Platforms]].

## What it does
- Sign in to one or more Jellyfin servers; several users per device as [[User Profiles]].
- Browse: [[Home Page]] (Continue Watching, Next Up, recommendations, recently added, favourites),
  libraries, brand hubs (Netflix, Disney+ … built from studio metadata), [[Search]].
- Details pages for movies, shows, seasons and episodes ([[Movies and TV Shows]], [[Episode Browser]]).
- Playback: Direct Play when the device can, server transcoding otherwise ([[Video Player]]), fast
  [[Playback Resume]], full ASS/SSA [[Subtitles]], intro/outro [[Skip Markers]] (Skip Intro / Skip Outro), Next Up, trickplay
  scrubbing thumbnails, Dolby Vision routing.
- [[Ratings]] (server critic score; optional MDBList/Trakt), [[Recommendations]], [[UI Sounds]].
- In-app [[App Updates]] from this repository's `latest-build` release.

## What it deliberately doesn't do
Live TV, music, photos, SyncPlay and Quick Connect are not implemented (see [[Known Issues]]).

## Related notes
[[Goals and Vision]] · [[Tech Stack]] · [[System Architecture]] · [[Server Overview]]
