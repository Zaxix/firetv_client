---
tags: [bug]
---
# Fixed Issues

Newest first. Each entry: symptom → cause → fix.

## UI polish update
| Symptom | Cause | Fix |
|---|---|---|
| First card ended up half under the sidebar after going right then back left | Rows span the full width under the sidebar; the default bring-into-view rule only kept cards 5% inside the *screen*, not inside the row's padding | `RowScroll`: a per-row `BringIntoViewSpec` using the row's own start/end padding ([[UI Layout Issues]]) |
| Row headings scrolled off the top when moving up | Only the focused card asked to be visible; the heading above it didn't | `revealOnFocus()` on each row brings the whole row (heading + cards) into view |
| Details hero only partly came back when moving up | Same as above for the hero item, plus the list stopped at the hero's buttons | `revealOnFocus()` on the hero and a settle-to-top after 450 ms |
| Episode descriptions not visible | Cards had no room for text | Fixed-height description panel under the episode row |
| Home rows took ~5 s on a slow server | Every row waited for the banner's library loads (up to 4 s) and then for the watch history (up to 2.5 s) | Rows show as soon as views/resume/next-up answer; the banner joins when ready; the history only gets what's left of 600 ms from Home opening ([[Home Page]]) |
| Recommendations downloaded the whole search index | Candidates were scored from the full title list | Server-side genre/unplayed filtered queries + rows cached per history signature |

## Earlier updates
| Symptom | Cause | Fix |
|---|---|---|
| Resuming an anime with `.ass` took ~20 s and could stall with buffer "ahead" | MKV font attachments (10–40 MB) before the first video cluster were re-read on every start | Keyframe start + header cache/prefetch ([[Playback Resume]]) |
| Right at a row's end / out of the sidebar jumped to the profile button | Geometric focus search found the only thing "to the right" | `FocusRegions` ([[Navigation Bugs]]) |
| Credits detected at the wrong time | The server reused one encode for the same play session across detection windows | A unique play session per window |
| Audio fingerprints didn't match across episodes | Time-difference codes broke with re-encoding | Band-shape codes (Hamming ≤ 4, smoothing) |
| Server unreachable with a VPN on | Full-tunnel VPN sent LAN traffic into the tunnel | `LanRoute` + racing known addresses ([[Server Connection and VPNs]]) |

## Related notes
[[Known Issues]] · [[Change Log]]
