---
tags: [feature, performance]
---
# Search

## What it does
Instant, typo-tolerant search over movie, series and collection titles, plus people (actors,
directors) from the server, and a per-profile history of searches that led somewhere.

## How it works
- `SearchService` downloads one lightweight list of every title (names, years, genres, one poster
  tag; no watch data) the first time Search opens, at most 20,000 titles, refreshed after 30 min.
- `SearchIndex` scores titles on the device: exact > prefix > all words as prefixes > typo matches
  (1–2 edits) > genre-only; years narrow ("alien 1979"). No request per keypress.
- People come from `/Persons?searchTerm=…`.

## Relevant files
`search/SearchIndex.kt`, `search/SearchService.kt`, `ui/screens/Browse.kt` (Search screen, chips),
`profiles/ProfileStores.kt` (SearchHistory).

## Dependencies
[[API Endpoints]] · [[User Profiles]]

## Data storage
`search-history-<profile>.json` (queries that led to an opened result).

## Performance considerations
One request per 30 min of searching instead of one per keypress; scoring a few thousand titles takes
milliseconds.

## User interactions
On-screen keyboard or a real keyboard; suggestion chips scroll sideways with the same edge padding
as other rows.

## Known limitations
Episode titles aren't in the on-device index (they're found through their series).

## Future improvements
Voice search on Fire TV.

## Related notes
[[Caching Strategy]] · [[Movies and TV Shows]]
