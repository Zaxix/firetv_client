---
tags: [bug]
---
# Fixed Issues

Newest first. Each entry: symptom → cause → fix.

## Settings text fields (Nov 2026)
| Symptom | Cause | Fix |
|---|---|---|
| Selecting a text field in Settings (TMDB key, MDBList, Trakt, the disc menu source…) and typing didn't stick: the page closed and Settings went back to Appearance | Reproduced on the Android TV emulator. Starting to type worked (the field took focus and the keyboard opened). Finishing (Back, or the keyboard's Next/Done) didn't: the field stopped being focusable before the highlighted box it returns to was back on screen. Focus was then nowhere, and the app's safety net put it on the first thing on screen, the "Appearance" section, which switches the page and throws away what was typed. The earlier fix (#3, handing focus over at the start) covered only the start | The highlighted box stays on screen and focusable while you type (clicks do nothing then); typing ends by moving focus back to it first, and only then does the field stop being focusable, so focus never goes nowhere. Checked on the emulator for the MDBList key (Back), TMDB key (Next) and the Disc menus source address; a permanent emulator check was added |

## Home navigation, Top 10 and Recommended (Nov 2026)
| Symptom | Cause | Fix |
|---|---|---|
| After going down Home to Recently Added and below, Up jumped to the profile button instead of the row above (Down could also stop dead) | Rows still loading (placeholders) and rows that loaded empty (zero height) stayed in Home's list. Compose's search for the next row only lays out about two screens' worth of extra list items when nothing is on screen, and these non-focusable items used that up, so it found nothing. The shell reads "Up found nothing" as "top of the screen" and opens the profile button | Empty rows aren't laid out; when the geometric search finds nothing, Home moves to the next row with cards itself (scrolling it into the list first) and only Up from the banner reaches the profile button. Reproduced and checked in `HomeNavigationTest` (CI: before the fix, Up from below three loading rows went to the profile button every time; Down stopped) ([[Focus Management]]) |
| Focus could vanish when the focused card's row reloaded or emptied | Nothing put it back except the shell's generic safety net (to whatever the content restored first) | Home puts it on the same row, else the nearest one |
| Top 10 didn't appear | Fewer than three titles watched in the household (by design, no row), only this TV's profiles counted, and the ranking ignored new viewing for up to 30 minutes (`invalidate()` was never called) | Administrators' Top 10 counts every account on the server; watching something refreshes it (at most once a minute); the history request no longer returns 300 unwatched titles for a new profile ([[Top 10 in Your Home]]) |
| Recommended didn't appear | A new profile was never offered recommendation rows; a failed request was saved as "no recommendations" and reused until something new was watched | New profiles get suggestions from favourites or the library's best-rated titles; partial results aren't saved and are tried again after a minute; a total failure shows Try Again ([[Recommendations]]) |
| A row's placeholder could stay forever if a request never answered | No limit on an attempt | 30 s per attempt, three attempts, then Try Again |
| Scrolling past a row while it loaded cancelled the load and started it again later | The load ran inside the row's own composition | Loads run in Home's scope |

## Performance audit (Nov 2026)
| Symptom | Cause | Fix |
|---|---|---|
| Switching seasons quickly could show the episodes of a season no longer selected | Each switch fetched its season; a slower earlier answer replaced the newer one | Answers for a season no longer selected are dropped, and seasons already seen come back from the page |
| The anime resume-and-seek CI check failed now and then ("ready 2x", then ended) | The seek keys sometimes arrived before the seek bar was up, so the first seek was lost and the second ran past the end | The check waits 1.5 s for the bar, and resets the resume point only after stopping the app |

## Episode orders and the Home banner (Nov 2026)
| Symptom | Cause | Fix |
|---|---|---|
| Playing a show (The Amazing World of Gumball) from Home started a random later episode, and after that its Next Up jumped ahead | The banner featured a show by its newest episode, and its Play started that episode; once watched, Jellyfin's Next Up (which follows the highest-numbered episode watched) moved past everything before it | The banner plays what the show's own Play would (the episode in progress or next, in the show's order, else the first) and More Info opens the show ([[Episode Orders]]) |
| Autoplay could try to play an episode the server only lists (not on disk) | Next Episode took the very next item | It skips episodes without a file |

## Subtitles, recommendations cache, air dates, skip markers (Nov 2026)
| Symptom | Cause | Fix |
|---|---|---|
| Signs & Songs subtitles chosen although a normal English track existed | Default mode with no default-flagged track took "a forced track" (release groups flag S&S forced); a blank subtitle language meant no preference; Media3/mpv could turn on the default/forced track before ours applied, and Media3 never retried a pick that wasn't among the first tracks | Dialogue ranking (full → SDH → other → S&S only if alone), blank = English, players ignore file flags and retry ([[Subtitles]]) |
| "Recommended For You" reloaded every time Home was opened | Choosing Home in the side menu builds a new Home; it re-read everything and re-requested the history, and the recommendation rows were merged in late | Per-profile plan in memory (stale-while-revalidate, deduplicated) and Home's rows kept for the session ([[Recommendations]]) |
| Skip Intro/Outro skipped up to ~0.9 s of story at its edges | The rough audio match's ±1 s smoothing set the edges | Frame-exact edges that never reach into the programme ([[Skip Markers]]) |
| A post-credits scene in the last 20 s could be skipped (credits extended to the end; "Next Episode" offered) | Credits music ending in the last 20 s counted as "runs to the end" | Only when silence follows or it's within 3 s; "Next Episode" only within 8 s and for confident credits |
| Weak (short/partial) matches were treated as certain | No notion of confidence | `SkipRange.confident`: weak ranges never auto-skip or replace Skip Outro |
| Markers were slow to appear | Audio windows fetched one after another; remembered markers waited for a server request | Parallel pairs, memory first ([[Skip Markers]]) |

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
