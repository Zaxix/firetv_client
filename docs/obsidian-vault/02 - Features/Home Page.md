---
tags: [feature, performance]
---
# Home Page

## What it does
The first screen after "Who's watching?". From top to bottom: the featured banner (hero), optional
brand tiles, then rows: **Continue Watching**, **Next Up**, the recommendation rows
("Because You Watched …", "Recommended For You"), **Recently Added in <library>** for each library,
and **Favorites**.

## How it works
`HomeScreen` in `ui/screens/Home.kt` runs one load when it first opens:

```mermaid
sequenceDiagram
    participant H as Home
    participant D as Device (home-cache file)
    participant S as Jellyfin
    H->>D: read last session's rows (in parallel)
    par
        H->>S: /UserViews
        H->>S: /UserItems/Resume
        H->>S: /Shows/NextUp
        H->>S: watch history (1 small /Items query)
        H->>S: /Items/Latest per movie/TV library (also feeds the banner)
    end
    Note over H: rows appear once views + resume + next up are in;<br/>history only if back within 600 ms of opening
    H->>H: banner joins when its loads finish (≤ 4 s)
    H->>D: save rows + banner (1.5 s later)
```

- **Saved copy first.** `HomeCache` (`home-cache-<server>-<user>[-p<profile>].json`) holds the
  last session's rows (≤ 12 items per row, slimmed to what cards need). It's read *alongside* the
  server requests and shown only if it wins the race, so an offline start still shows Home.
- **Rows don't wait for each other.** Each row is a `RailState` that loads itself
  (`ensureLoaded()`, up to 3 attempts) when composed; rows not loaded yet keep last session's items
  (marked stale) instead of a skeleton.
- **Banner is separate.** It reuses the same "Recently Added" responses (one request per library,
  not two), so it costs no extra requests, and it never holds the rows up.
- **Recommendations don't block.** The history request is sent with the others. Its rows are placed
  with the rest only if it's back by then (or within `REC_WAIT_MS`, 600 ms, of Home opening; the
  budget counts from the start, so it never adds to a slow server's wait). Otherwise the rows show
  without it and the recommendation rows are merged in when it arrives.
- **Refresh after watching.** `WatchActivity.version` changes when playback stops or something is
  marked watched/favourite; Home then refreshes only Continue Watching, Next Up and recommendations,
  keeping every other row and the focused card (`mergeRails`).
- **Play gets ready early.** Resting on the banner or a Continue Watching / Next Up card for 700 ms
  asks the `PlaybackPrewarmer` to prepare that title (see [[Playback Resume]]).

## Relevant files
`ui/screens/Home.kt` (HomeScreen, RailState, HomeCache, arrangeRails/mergeRails),
`ui/components/Common.kt` (ContentRail, SkeletonRail), `ui/components/Cards.kt`,
`recommend/Recommendations.kt`, `brands/Brands.kt`, `data/WatchActivity.kt`.

## Dependencies
[[API Endpoints]] · [[Recommendations]] · [[Caching Strategy]] · [[Focus Management]] ·
[[Frosted Glass Effects]] (Home draws its own backdrop from the focused card).

## Data storage
`home-cache-….json` per profile (removed with the profile). No other Home state is persisted;
the screen model lives in the back-stack entry so returning to Home is instant.

## Performance considerations
- One request per library for "Recently Added", shared with the banner.
- Rows lazily compose; each `LazyRow` pre-composes half a screen ahead and a quarter behind
  (`LazyLayoutCacheWindow(ahead = 0.5, behind = 0.25)`), not whole rows.
- Artwork is requested at the card's real pixel size, so images are as small as they can be.
- CI logs `Perf: Home rows from the server in X ms` (rows placed), `Home rows on screen filled
  from the server in X ms` and the requests Home made in its first 10 s (`Server requests in the first 10 s: …`); see [[Change Log]] for measurements.

## User interactions
D-pad/arrow keys move between cards and rows; Up from the top row reaches the banner; Up from the
banner opens the profile button ([[Navigation System]]). OK opens a title; Play on the banner
starts it.

## Known limitations
- The very first launch on a device has no saved copy, so rows show skeletons until the server answers.
- Recommendation rows can appear a moment after the others on a slow server (by design).

## Future improvements
- Use the server's `/Items/Latest` `groupItems` counts to show "new episodes" badges.

## Related notes
[[Home Page Layout]] · [[Performance Optimization]] · [[Recommendations]] · [[User Profiles]]
