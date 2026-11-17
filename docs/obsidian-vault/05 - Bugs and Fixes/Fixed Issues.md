---
tags: [bug]
---
# Fixed Issues

Newest first. Each entry: symptom → cause → fix.

## Platform cleanup, outros, recommendations, temporary files
| Symptom | Cause | Fix |
|---|---|---|
| Live-action show: intro found, outro never | Closing credits over different music each episode share nothing to hear; only one neighbour was compared; and once an intro-only result was remembered, the outro was never searched again | On-screen credits check from trickplay thumbnails (confirmed by a neighbour), a second neighbour for audio, and markers remember which kinds were searched ([[Skip Markers]]) |
| An episode with nothing detectable was re-analysed on every resume | Only found markers were stored | "Searched, nothing found" is remembered too |
| "Next Episode" during the credits skipped a post-credits scene | The credits prompt always offered the next episode | It's **Skip Outro** (lands on the scene) unless the credits run to the end |
| "Because You Watched Charlotte" offered The Inbetweeners and Adventure Time | One shared genre was enough; no notion of anime vs. cartoon vs. live action | Content-kind-aware similarity score with a threshold ([[Recommendations]]) |
| Non-anime titles could start in a foreign-language track | With no audio language set, the file's/server's default track was used | *Automatic*: English except for anime ([[Video Player]]) |
| Saved MKV starts stayed for finished episodes until the 256 MB cap pushed them out | The header cache was size-limited only | Deleted when finished, or 5 days after last left unfinished ([[Temporary File Lifecycle]]) |
| mpv's copies of server fonts stayed after playback; an installed update APK stayed in the cache | Never cleaned | Removed when the player closes / at the next start |

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
