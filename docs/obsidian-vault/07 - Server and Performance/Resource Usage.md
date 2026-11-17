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
| MKV header cache (Android) | ≤ 256 MB |
| Per-profile JSON files | KBs each (watch history ≤ 3,000 entries) |
| UI sounds | ~7 KB |

## Memory
Artwork memory cache ≤ 20% of app memory; trickplay keeps only a few compressed sheets in memory;
lazy rows compose half a screen ahead.

## Related notes
[[Caching Strategy]] · [[Optimization Notes]]
