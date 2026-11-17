---
tags: [architecture, performance]
---
# Caching Strategy

Guiding rule: cache what saves the **server** work or saves the viewer waiting, keep caches small,
and never cache anything that could go stale silently.

| Cache | Lifetime | Saves |
|---|---|---|
| Back-stack screen models | Until the entry is popped | Re-fetching when going Back |
| Home saved copy | Replaced every visit | Blank Home at launch; works offline |
| Recommendation rows | While the history signature is unchanged | Candidate queries on most launches |
| Search index | 30 min | One request per keypress |
| Brand list / hubs | 30 min / 10 min | Repeated studio queries |
| Ratings | Session (failures 5 min) + 1 week per profile | Third-party calls |
| Playback prewarm | One use, ≤ 90 s, same session/settings | PlaybackInfo wait on Play |
| MKV headers | LRU 256 MB, keyed by file id+size+ETag | Re-downloading 10–40 MB of fonts on every resume |
| Artwork | Coil memory + 300 MB disk; BlurHash placeholders | Image downloads; blank cards |
| Trickplay sheets | Compressed on disk, last few in memory | Decoding whole sprite sheets |

Things deliberately **not** cached: library contents beyond the Home copy (always fresh from the
server), video data, anything with a token.

## Related notes
[[Performance Optimization]] · [[Data Storage]] · [[Resource Usage]]
