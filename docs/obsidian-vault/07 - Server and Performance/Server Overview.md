---
tags: [performance]
---
# Server Overview

Sharkfin talks to the user's own **Jellyfin 10.9+** server; there's no other backend. The server's
hardware is modest, so the rule throughout the app is: **do the work on the device, ask the server
only for what's needed, never make it transcode something nobody pressed Play on.**

What the server does for Sharkfin:
- Library queries and images ([[API Endpoints]]).
- Playback decisions (`PlaybackInfo`) and, only when needed, remuxing/transcoding.
- Watch state for account profiles (device profiles keep theirs on the device).
- Optional: media segments (Intro Skipper), trickplay sheets.

## Related notes
[[Hardware Limitations]] · [[Resource Usage]] · [[Server Connection and VPNs]]
