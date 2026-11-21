---
tags: [development, changelog]
---
# Change Log

Newest first. Numbers come from CI (Android TV emulator + a real Jellyfin server in Docker), not
from a real Fire TV.

## Top 10 in Your Home
A Home row of the ten films and shows watched most in the household:
- **Whose viewing counts:** every profile on this device, on the current server.
- **Ranking:** time watched in the last 30 days, then all-time.
- **What each profile sees:** only titles it's allowed to see.
- **Look:** posters standing in front of tall outlined numerals.

One request per account profile, at most every 30 minutes; no internet needed. See
[[Top 10 in Your Home]].

## Episode orders, and the Home banner's Play
### Episode orders
Shows that aired out of order can be shown, browsed and played in another order, across seasons
too, without touching Jellyfin, the files or anyone's progress.
- **Automatic:** a documented intended order, else the DVD order, from TMDB (with the viewer's free
  TMDB key, Settings → Ratings). Used only after its numbers and titles are checked against the
  library. Looked up at most weekly from the TV, and kept on the device for when TMDB or the
  internet is down.
- **By hand:** **Episode Order** on a show's page. Choose Automatic, Jellyfin's order, any TMDB
  order or Custom; move episodes with the remote (Up/Down one place, Left/Right to another season),
  type a position, add a season, save, or reset to Jellyfin's order.
- **Used everywhere:** season tabs and rows, Next Episode and autoplay (across seasons), Next Up,
  the player's episode menu, and the numbers on cards. Episodes keep their ids, so the right video
  plays and progress stays attached.
- **Works with split files:** each part of a file holding several episodes takes its own place.

See [[Episode Orders]].

### The Home banner's Play (the Gumball "random episodes" problem)
- **Before:** the banner featured a show by its newest episode and played that episode. Once it was
  watched, Jellyfin's Next Up (which follows the highest-numbered episode watched) jumped past
  everything before it.
- **Now:** a show on the banner plays what its own page's Play would, and More Info opens the show.
- **Also:** Next Episode skips episodes the server lists without a file.

## Several episodes in one file, and film fonts for subtitles
### Virtual Episode Splitting
Files holding two or more episodes (common with cartoons) are shown and played as separate episodes.
- Each has its own number, title, length, thumbnail, progress, watched mark, Continue Watching card,
  Next Up and autoplay.
- Files the server lists once are split into virtual episodes; several server entries sharing one
  file each play their own part.
- Boundaries come only from chapters that name the episodes. A file is never split in half; anything
  unclear stays one playable entry and can be split by hand (**Split Episodes** on the episode's
  page, with previews).
- Progress is kept per profile on the device, so the server's position for the file is never mixed
  up. The server only learns "watched".
- The player shows each episode's own 0:00–end timeline. The engines stop exactly at its end, and
  seeks and skip markers stay inside it. Subtitles and audio are the file's own, so they stay in
  sync.

See [[Virtual Episode Splitting]].

### Film fonts for subtitles
The Harry Potter films' normal subtitles now use IM Fell English (OFL, bundled), a storybook-style
printed face, in both players. Styled ASS and picture subtitles are unchanged, and **Settings →
Subtitles → Film fonts** turns it off. See [[Subtitles]].

### Verified in CI
- Unit tests: `VirtualEpisodesTest`, `SubtitleThemesTest`.
- Real Jellyfin (sample series *Paper Town*):
  - the file with chapters named "Episode 1/2" is split at 4:00;
  - the one without stays whole;
  - next-episode order, Direct Play of the file, independent progress, and the server's file left
    untouched.
- Emulator: playing the second virtual episode, and the film font on a sample film in the
  collection. See the CI notes in [[Testing Guide]] and [[Known Issues]] for what wasn't checked.

### Performance check (CI emulator, previous release vs this update, run at the same time)
| | Previous release | This update |
|---|---|---|
| App's first frame (before any of this code runs: the runner's speed) | 385–433 ms | 479–553 ms |
| Home cold start, rows shown | 1014–1087 ms (475 once) | 1181–1449 ms (597 once) |
| Home cold start, API requests | 11–14 | 9–14 |
| Resume from the detail page (L2) | 1363, 1850 ms | 1631, 1757 ms |
| Resume from a deep link (L2) | 1989, 2125 ms | 2607, 2742 ms |
| Prepare paths (item + PlaybackInfo) and engine requests per start | 12 DirectPlay / 2 Transcode; 3–5 requests | identical |
| Home opened again | 5–14 ms, 0/0/3 requests | 6–17 ms, 0/0/2 requests |

This update's run landed on a slower runner: its first frame, which no new code touches, was about
25% slower, and its other times were 20–30% slower too. The request counts and preparation paths are
identical, and the new code on an ordinary title's path is a few in-memory lookups (no split → lists
returned as they are; no film font → nothing loaded). So no regression is attributable, but this
couldn't be proven on equal runners.

## Subtitles, Home recommendations cache, upcoming air dates, faster and more accurate skip markers
### Subtitles
Signs & Songs is no longer chosen when a normal English track exists: dialogue ranking (full →
SDH/CC → other → S&S/forced only if alone), blank language = English, and the players no longer turn
on a file's default/forced track themselves. See [[Subtitles]].

### Home: "Recommended For You" kept per profile
The history plan is kept in memory per profile (fresh until something is watched/marked here, or
30 min), deduplicated, and shown stale while a new one loads. Home's own rows are kept for the
session, so opening Home again from the side menu requests nothing for 5 minutes.
See [[Recommendations]], [[Home Page]].

### Upcoming air dates
- Details page: "Continuing — Next Episode: 24 November 2026" (yellow).
- Episode browser: "Airs …" on dimmed, non-playable cards; seasons show "Premieres …" or
  "Expected …".
- Sources: the server's own metadata (`/Shows/Upcoming`), plus Trakt's exact time when a key is set.
  Calendar dates are never shifted by the time zone.

See [[Details Page Layout]], [[Episode Browser]].

### Skip markers
- Remembered markers show before any request.
- Audio windows are fetched two at a time, and each server encode ends once its window is read.
- Edges are frame-exact and never reach into the story.
- Post-credits scenes are protected, and weak matches are never auto-skipped.

See [[Skip Markers]].

### Measured (CI; "before" = the previous release's code with the same measurements added, run twice)
| | Before | After |
|---|---|---|
| Home opened again: rows on screen | 81 / 127 / 51 ms; 144 / 75 / 82 ms | 5 / 6 / 6 ms; 5 / 8 / 13 ms |
| Home opened again: API requests in first 10 s | 10 / 13 / 10; 14 / 10 / – | 0 / 0 / 2; 0 / 0 / 3 |
| Home cold start, rows shown (6 starts per run; the ~0.4 s ones are when the saved copy was already in memory) | 413–1140 ms; 475–1168 ms | 1028–1162 ms; 1156–1246 ms |
| Home cold start API requests | 11–13; 11–13 | 11–14; 11–14 |
| Listening detection (Wavelength ep 2, integration test) | 2683, 2702, 2671 ms | 1307, 1208 ms |
| On-screen outro (Harbor Precinct ep 2, whole detection) | 1477, 1061, 1975 ms | 2630, 1729, 766, 1787, 1696, 788 ms (no clear change: noisy) |
| Intro edges (true 8–38 s) | 7.375–38.5 s | 8.0–37.625 s |
| Credits start (true 215 s) | 214.144 s | 215.144 s |
| Resume, current path, from detail page (L2) | 1356, 1286; 1770, 1869 ms | 1462, 1448; 1693, 1722 ms |
| Resume, current path, deep link (L2) | 1917, 1890; 2021, 2263 ms | 2093, 2034; 2324, 2160 ms |
| Anime resume from detail page | 1702; 1567 ms | 1530; 1641 ms |

The resume ranges overlap run to run (the second "before" run was itself slower than the first), so
no playback change is attributable to this update. Home's cold start: the first "after" run matched
"before", the second was ~60–100 ms slower than the typical "before" start; nothing in this update
runs before Home's rows on a cold start beyond reading the (empty) memory maps, so this reads as
runner variation, but it's noted here as unproven either way. Not measured on a Fire TV.

The run that published this build (same code) landed on a slower runner: everything was ~30–50 %
slower, including the app's first frame (537–663 ms, against 372–507 ms in all four runs above), which
happens before any code this update touched. That run gave Home shown 1421–1615 ms and L2 resume
1942–3289 ms. Home opened again was still 8–9 ms with 0 / 0 / 3 requests, and every test passed.

## Platform cleanup, outros, recommendations, temporary files, audio default
### Removed: the Windows app
The `desktopApp` module, the libmpv/JNA/DPAPI/updater/sound code for Windows, the Windows CI job,
the desktop screenshot step and the Windows downloads. The shared module's JVM target stays as the
test host (`:shared:desktopTest` → `:shared:jvmTest`). See [[Supported Platforms]].

### Temporary files
Saved MKV starts are deleted when an episode is finished, kept 5 days when it's left unfinished
(renewed on each resume), never while playing or while another profile holds them. mpv font copies
go when the player closes; an installed update APK goes at the next start.
See [[Temporary File Lifecycle]].

### Outros (Skip Outro)
- Closing credits found from the server's trickplay thumbnails (text on black, confirmed by a
  neighbouring episode) for shows whose credits music changes; a second neighbour for audio.
- Markers remember which kinds were searched (misses too): no repeated analysis on resume, and an
  intro-only result no longer blocks the outro search.
- The next episode is compared first, so its markers come out of the same comparison.
- "Skip Outro" lands on a post-credits scene; "Next Episode" only when the credits run to the end.
See [[Skip Markers]].

### Recommendations
Content-kind-aware similarity (anime / animation / live action), weighted genres, tags and
descriptions, audience, with a threshold; short rows of close matches instead of padding.
See [[Recommendations]].

### Audio default
*Automatic* (default): English for everything except anime, which keeps its original audio.
See [[Video Player]].

### Measurements (CI)
| What | Result |
|---|---|
| Outro on screen, live-action test series (credits 170–215 s, post-credits scene after) | found 170.0–211.0 s, via "credits on screen", 986 ms on the real CI server |
| Intro + outro by listening (Wavelength Street ep. 2, compared with ep. 3 now) | intro 7.4–38.5 s (true 8–38), credits from 214.1 s (true 215), 2.7 s |
| Recommendations, synthetic library (two profiles) | A: Recommended For You = Tidewater, Velvet Static, Ember Field, Wavelength Street; B: no row (fewer than 3 close matches) |
| Charlotte case (unit test) | Angel Beats!, Erased shown; The Inbetweeners, Adventure Time, a one-genre anime not shown |
| Home rows placed (6 cold starts, CI emulator) | 1.17–1.41 s (previous update: 1.08–1.30 s; before it: 1.20–1.35 s) — unchanged within runner noise |
| Home API requests in the first 10 s | 9–14 (previous update: 9–14) |
| Anime resume (prepared from details / cold) | 1.76 s / 2.04 s (previous update: 1.50 s / 2.06 s) — unchanged within noise |

## Profiles without new server accounts
- **"Add profile" no longer needs a Jellyfin account.** A profile is now a name and a picture
  added on the device under the sign-in already in use. No administrator access is needed.
- Each device profile has its own Continue Watching, Next Up, watched marks, favourites and
  recommendations. These are stored on the device (`watch-<profile>.json`); the account's history
  on the server is never changed.
- "Sign in another account" is still available for separate Jellyfin users.
- Files:
  - `data/SessionManager.kt` (LocalProfile)
  - `jellyfin/LocalWatchData.kt`
  - `profiles/LocalWatchStore.kt`
  - `jellyfin/JellyfinApi.kt` (overlay)
  - `ui/screens/Profiles.kt`
- Tests: `LocalProfilesTest`. See [[User Profiles]].

## UI polish, Home speed, navigation sounds
### Fixed
- First card no longer slides under the sidebar after going right and back left (`RowScroll`).
- Row headings stay visible when moving up (`revealOnFocus`).
- The details hero fully reappears when moving back up. The page also settles to the top.
- Episode descriptions show under the episode row, in a fixed-height panel.

See [[Fixed Issues]] and [[UI Layout Issues]].

### Faster Home with less server work
- **Before:** every server row waited for the banner's library loads (up to 4 s) and then for the
  watch history (up to 2.5 s).
- **After:**
  - Rows appear as soon as Continue Watching and Next Up answer.
  - The banner joins when its loads finish (it already shared the rows' requests).
  - The history only gets what's left of 600 ms from Home opening.
  - Recommendations use small server-filtered queries, and their rows are reused while the
    history is unchanged (no catalogue download).
  - Row pre-composition is trimmed to half a screen ahead.
- Measured: see the table below.

### Navigation sounds
- Original tick (move) and chime (select), bundled locally.
- Settings → Appearance → Navigation sounds / Sound volume.
- One sound per key press, no overlap, nothing during playback. See [[UI Sounds]].

### Documentation
- This vault (`docs/obsidian-vault/`).

### Measurements
CI emulator against a CI Jellyfin server on the same machine (fast, so it can't show the slow-server
waits that were removed; on a slow server rows no longer wait up to 4 s + 2.5 s).

| Metric (6 cold starts each) | Before (cd0cd1c / 7058b5b) | After (2e7febb) |
|---|---|---|
| Home rows placed from the server | 1.20–1.35 s | 1.08–1.30 s |
| API requests in Home's first 10 s, first launch after watching | 18 (+4 images) | 11–14 (+0–2 images) |
| API requests in Home's first 10 s, later launches | not captured | 9–11 |
| Anime resume (prepared from details / cold) | 1.48 s / 2.97 s | 1.50 s / 2.06 s (kept) |

A "rows on screen filled" measure added in that update never completed (at launch the banner fills
the screen, so no row is visible yet); it was removed in the next update.

## Earlier
- **Anime resume:**
  - The start of font-heavy MKVs is kept on the device and fetched ahead.
  - Measured: 2.96 s / 36.9 MB → 1.39 s / 1.95 MB.
- **Resume speed:**
  - Keyframe start, shared connections, and item + PlaybackInfo fetched in parallel.
  - Measured: 4.4–5.2 s → 2.9–3.0 s cold.
- **Skip markers without plugins:** audio fingerprinting, recurring chapters, AniSkip.
- **VPNs:**
  - Known addresses are raced.
  - `LanRoute` handles full-tunnel VPNs.
  - "Refused" errors are now clear.
- **Navigation:**
  - `FocusRegions`: the sidebar, row ends and the profile button.
  - Personalized recommendations per profile.

## Related notes
[[00 - Home]] · [[How to Update This Vault]]
