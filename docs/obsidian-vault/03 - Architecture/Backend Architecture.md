---
tags: [architecture]
---
# Backend Architecture

Sharkfin has **no backend of its own**. Everything comes from the user's Jellyfin server, plus a few
optional third-party services the user turns on.

| Talks to | For | Token sent? |
|---|---|---|
| Jellyfin (the user's server) | Library, images, playback, watch state | Yes, in request headers only |
| MDBList / Trakt (optional keys) | Ratings and reviews | No (their own key only) |
| AniList / AniSkip (optional) | Anime skip times | No |
| GitHub releases (`latest-build`) | In-app updates | No |

- `JellyfinApi` (Ktor) is a typed client; `SessionManager` holds servers, users and tokens and
  creates one `JellyfinApi` per active profile.
- **Connections:** on Android, one OkHttp pool (`SharedOkHttp`) serves API calls, artwork and the
  video stream, with room for a screen's worth of parallel requests per host. `LanRoute` reaches a
  home-network server while a full-tunnel VPN is on (see [[Server Connection and VPNs]]).
- **Device-only profiles** add a client-side overlay (`LocalWatchData`) instead of server accounts;
  see [[User Profiles]].

## Related notes
[[API Endpoints]] · [[Server Overview]] · [[System Architecture]]
