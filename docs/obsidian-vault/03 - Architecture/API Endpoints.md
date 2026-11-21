---
tags: [architecture]
---
# API Endpoints

Every Jellyfin route Sharkfin calls (from `jellyfin/JellyfinApi.kt`). Requires Jellyfin 10.9+.

| Route | Used for |
|---|---|
| `GET /System/Info/Public` | Finding the server, checking an address answers as the same server |
| `GET /Users/Public` · `POST /Users/AuthenticateByName` · `GET /Users/Me` · `POST /Sessions/Logout` | Sign-in, token check, sign-out |
| `GET /UserViews` | Libraries (Home, sidebar) |
| `GET /Items` | Grids, rows, search index, recommendations, favourites, history; device profiles use `ids` / `excludeItemIds` |
| `GET /Items/{id}` | One title |
| `GET /Items/Latest` | "Recently Added" rows and the Home banner |
| `GET /UserItems/Resume` | Continue Watching (account profiles) |
| `GET /Shows/NextUp` | Next Up (account profiles) |
| `GET /Shows/{id}/Seasons` · `/Shows/{id}/Episodes` | Episode browser, Next Up for device profiles, chapters for skip detection |
| `GET /Shows/{id}/Episodes` (no season) | Once a minute at most for a show in another episode order: all its episodes, arranged on the device ([[Episode Orders]]) |
| TMDB `GET /3/tv/{id}/episode_groups`, `/3/tv/episode_group/{id}` | Outside service, from the TV, with the viewer's key: a show's alternative orders, at most weekly ([[Episode Orders]]) |
| `GET /Items?ids=…&fields=Chapters,Path` | Once per season with possible multi-episode files: their chapters ([[Virtual Episode Splitting]]) |
| `GET /Shows/Upcoming?parentId={series}` | Air dates of a series' upcoming episodes (details page, episode browser) |
| `GET /Items/{id}/Similar` | "More like this", "Because You Watched" |
| `GET /Persons` · `GET /Studios` | Search people · brand tiles |
| `POST/DELETE /UserFavoriteItems/{id}` · `/UserPlayedItems/{id}` | Favourite / watched (account profiles) |
| `POST /Items/{id}/PlaybackInfo` | Playback negotiation |
| `GET /Videos/{id}/stream` · HLS URLs from PlaybackInfo | Video |
| `GET /Audio/{id}/stream` | Low-rate audio windows for skip detection |
| `GET /MediaSegments/{id}` | Server intro/credits markers |
| `POST /Sessions/Playing` · `/Progress` · `/Stopped` | Playback reporting (account profiles) |
| `POST /Sessions/Playing/Ping` | Keep-alive during a device profile's playback |
| `DELETE /Videos/ActiveEncodings` | Stop a transcode when playback ends, and end a skip-detection audio encode as soon as its window is read |
| `GET/POST /DisplayPreferences/{id}` | Syncing rating-service keys between devices |

Images use `ImageUrls` (`/Items/{id}/Images/{type}` with `fillWidth`, tag and quality).

## Related notes
[[Backend Architecture]] · [[Resource Usage]]
