---
tags: [feature]
---
# Watch History

## What it does
Resume points, watched marks, favourites, Continue Watching and Next Up.

## How it works
- **Account profiles:** Jellyfin keeps everything. The player reports
  `/Sessions/Playing` (start), `/Sessions/Playing/Progress` (every 10 s and on pause) and
  `/Sessions/Playing/Stopped`; "Mark watched" and favourites use `/UserPlayedItems/{id}` and
  `/UserFavoriteItems/{id}`.
- **Device profiles:** `LocalWatchStore` keeps, per item, position, watched, favourite, when, runtime
  and series. Same rules as Jellyfin: under 5% isn't a resume point; 90% or more counts as watched
  (and clears the position). Unmarking a series unmarks its episodes. Oldest entries go beyond 3,000.
- `WatchActivity.changed()` tells Home to refresh its watch-dependent rows.

## Relevant files
`playback/PlayerController.kt` (reporting), `jellyfin/JellyfinApi.kt`,
`profiles/LocalWatchStore.kt`, `data/WatchActivity.kt`, `ui/screens/Detail.kt`.

## Dependencies
[[User Profiles]] · [[Video Player]] · [[API Endpoints]]

## Data storage
Server (accounts) or `watch-<profile key>.json` (device profiles; a few hundred bytes per title).

## Performance considerations
The device store is copy-on-write in memory and writes one small file per change.

## User interactions
Resume prompt on details; Watched/Favourite buttons; Continue Watching section in the sidebar.

## Known limitations
See [[User Profiles]] for device-profile limits.

## Future improvements
Remove single items from Continue Watching.

## Related notes
[[Playback Resume]] · [[Recommendations]]
