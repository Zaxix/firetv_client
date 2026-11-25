---
tags: [performance]
---
# Resource Usage

## Server requests
Home's first 10 s are counted on Android (API / images / media) and logged by CI; see
[[Change Log]] for before/after numbers. Steady state: Home ≈ one request per row plus the
banner's shared loads; recommendations usually one history request (rows reused while the history
is unchanged).

## Device storage (worst case)
| Item | Size |
|---|---|
| Artwork disk cache | ≤ 300 MB |
| MKV header cache | ≤ 256 MB, and now only for episodes in progress (deleted when finished or 5 days after last left; [[Temporary File Lifecycle]]) |
| mpv font copies | deleted when the player closes |
| Downloaded update APK | deleted once installed |
| Per-profile JSON files | KBs each (watch history ≤ 3,000 entries) |
| UI sounds | ~7 KB |

## Skip detection (per episode analysed, only when the server has no markers)
| Step | Server work | Device work |
|---|---|---|
| Chapters | one episode-list request (shared by all steps) | trivial |
| Audio | intro: 2 windows ≤ 10 min; outro: 2–3 windows ≤ 4 min, low-rate audio (~4 KB/s) | FFT fingerprints, < 1 s |
| Screen (outro only, if still missing) | 1–2 static trickplay JPEGs per episode (2 episodes) | decode at half size + pixel statistics, < 1 s |

Results are remembered (found or not) while the episode is in progress, and the next episode gets
its markers from the same comparison.

## Memory
Artwork memory cache ≤ 20% of app memory; trickplay keeps only a few compressed sheets in memory;
lazy rows compose half a screen ahead.

## Related notes
[[Caching Strategy]] · [[Optimization Notes]]
