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
| Recommendation rows | While the history signature is unchanged (memory + disk, card fields only) | Candidate queries on most launches |
| Recommendation history plan | Per profile in memory: until something is watched/marked/favourited here, or 30 min; stale shown while a new one loads | The history requests every time Home opens |
| Home rows (`HomeSnapshot`) | Session, last 2 profiles; refreshed when 5 min old | Every request Home makes when opened again from the side menu |
| Upcoming air dates | 6 h per series in memory (50 series), aired ones dropped on read | A request per series page |
| Season episode lists (skip detection) | 10 min, 4 seasons | Chapters/trickplay lookup for the next episode |
| Skip-detection audio windows | Last 4 windows in memory | Fetching a neighbour's audio twice |
| Search index | 30 min | One request per keypress |
| Brand list / hubs | 30 min / 10 min | Repeated studio queries |
| Ratings | Session (failures 5 min) + 1 week per profile | Third-party calls |
| Skip markers (found and "searched, nothing found") | While the episode is in progress (30 days max) | Re-analysing an episode on resume |
| Series kind (anime or not), for the audio default | App session, 64 series | A series request per episode |
| Playback prewarm | One use, ≤ 90 s, same session/settings | PlaybackInfo wait on Play |
| MKV headers | Until finished, or 5 days after last left unfinished; LRU 256 MB cap ([[Temporary File Lifecycle]]) | Re-downloading 10–40 MB of fonts on every resume |
| Artwork | Coil memory + 300 MB disk; BlurHash placeholders | Image downloads; blank cards |
| Trickplay sheets | Compressed on disk, last few in memory | Decoding whole sprite sheets |

Things deliberately **not** cached: library contents beyond the Home copy (always fresh from the
server), video data, anything with a token.

## Related notes
[[Performance Optimization]] · [[Data Storage]] · [[Temporary File Lifecycle]] · [[Resource Usage]]
