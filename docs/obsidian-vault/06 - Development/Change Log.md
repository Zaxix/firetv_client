---
tags: [development, changelog]
---
# Change Log

Newest first. Numbers come from CI (Android TV emulator + a real Jellyfin server in Docker), not
from a real Fire TV.

## Bundled highlight crops
The 52 tiny highlight images of the disc menu pack are one `.bundle` file (`AssetBundle`, `make_bundle.py`);
the app reads each member by its old path, so `pack.json` is unchanged and old unbundled packs still work.
A pack with a bundle needs this build. Reason: after the menu opened, the last 30% of the download was
68 small files fetched one by one. Not measured on a device.

## Smaller disc menu pack
The first pack went from 20.6 MB to 6.4 MB, and the part the menu waits for from 15.2 MB to 4.5 MB,
by re-encoding on the build machine (HEVC video, 96 kbps Opus, WebP q85 sparkle frames, q84 plates).
Durations, frame counts, fps and sprite rectangles are unchanged; similarity numbers are in
[[Disc Menus]]. No app code changed (only the test fixture of the manifest). Not tried on a Fire TV.

## Disc menus (first version)
**Play on a film can now open a DVD-style menu** instead of starting the video: main menu with
sparkles and music, scene selection with the film's chapters, cast and crew from the server,
languages / subtitles, the cloud intro and the Play Movie letter. First pack: Harry Potter and the
Philosopher's Stone (UK, disc 1). Details in [[Disc Menus]].

**Changes:**
- **New:** `discmenu` package (state machine, screen, media layer, pack loader), a "Disc menus"
  switch in Settings (shown only when a pack is installed), `Route.DiscMenu`, and a start position
  and subtitle override on `Route.Player`. Films without a pack are unaffected.
- **Size:** the pack is about 20 MB (the first Electron recreation was 171 MB zipped, about 58 MB of
  assets). Video stays H.264 for hardware decoding; music is Opus.
- **Tests:** 234 unit tests pass on CI (`:shared:testDebugUnitTest`), including the menu's state
  machine and the real pack's data. They found two real mistakes: scene buttons on page 2 were
  labelled with page-1 chapter numbers, and a bad "entry default" in a pack would have thrown
  instead of falling back. Both fixed.
- **Download as it plays:** the pack can be fetched from a source address (Settings → Disc menus)
  while the intro plays, and is deleted from the device when the menu is left (unless kept). New:
  `PackManifest` / `PackCatalog` (file list with size, SHA-256 and stage), `PackInstall`
  (resumable, retried, in stage order), `AndroidPackStore`, a catalog cache refreshed in the
  background from the film page, a Disc menus settings page, and `tools/disc-menu/make_manifest.py`.
  The menu's loop and music players are now created only once their files exist.
- **Not done / not verified:** nothing here has run on a Fire TV, and downloading has not been
  tried against a real host. See "Known limitations" in
  [[Disc Menus]] (sparkle blending over video, looping, three decoders, assumed actions).

## Fewer repeated server requests; default disc menu address
- **Fewer repeated requests:**
  - Up to 4 titles are kept prepared to play (was 1).
  - Preparing in the background asks for PlaybackInfo once, not twice, when a file needs transcoding.
  - Whole items, a show's seasons, its episode lists and its Next Up are kept for 30 s, and cleared
    whenever anything is watched, marked or favourited.
  - The banner's Play target is kept between visits to Home.
  - The update check waits 4 s after launch.
- **Measured (1.0.170 CI, three before/after pairs):**
  - Server requests per round went from 78–91 to 60–66 (about −23%), and exact repeats halved
    (25 → 13).
  - A reopened film page went from 64 ms to 5 ms.
  - Memory is about 10 MB higher.
  - Details in [[Performance Optimization]].
- **Default disc menu address:** the release APK fills in the disc menu address once per profile.
  - Debug and test builds carry neither the address nor the token.
- **Tests:** `ItemCacheTest`, `BuiltInSourceTest`, new `PrepareTest` cases.

## Commit next to the version, old APK copies dropped, built-in disc menu token
- **Version with commit:** the app shows the commit next to the build number, e.g. 1.0.158 (abc1234).
- **Published files:** latest-build no longer includes `Nameless-release.apk` or `AuroraTV-release.apk`.
- **Built-in disc menu token:** every install can start with one, taken from the `DISC_MENU_TOKEN`
  repository secret.
  - It's used only for GitHub addresses, and only when no token has been entered.
  - It's never in the source.
- **Tests:** `BuiltInTokenTest`.

## Settings text fields
**Typing in a Settings field threw you back to Appearance.** Finishing typing (Back, or the
keyboard's Next/Done) dropped focus for a moment. The app then put focus on the first section,
which switched the page and lost the text. Now focus goes straight back to the field's highlighted
box, which stays on screen.

**Checks:**
- Emulator, before and after the fix: the MDBList key, the TMDB key and the Disc menus source
  address.
- A new permanent emulator check covers Settings typing.

**Not checked:** the Fire TV's own keyboard.

## Home navigation, Top 10 and Recommended
**Up from the lower rows of Home went to the profile button.** Cause: rows still loading and rows
that had loaded empty stayed in Home's list. When enough of them sat between two real rows,
Compose's search for the next row (which only looks about two screens' worth of list items ahead)
found nothing, and the shell treats "Up found nothing" as the top of the screen. Reproduced in a
JVM test before the fix: with three rows loading, Down stopped and Up went straight to the profile
button.

**Changes:**
- **Navigation:** empty rows aren't laid out. When the normal move finds nothing, Home moves to the
  next row with cards itself, scrolling it into view first if needed. Only Up from the banner
  reaches the profile button.
- **Focus recovery:** when the focused card disappears, focus goes back to its row, or else to the
  nearest one.
- **Loading rows:** a row's load is no longer cancelled by scrolling past it. Each attempt gives up
  after 30 s, and after three attempts the row shows Try Again instead of a placeholder that never
  goes away.
- **Top 10:**
  - With administrator rights, it counts every account on the server.
  - Watching something refreshes it, at most once a minute (that refresh was never wired up).
  - It never invents a ranking; with fewer than three titles watched, the row isn't shown.
  - A profile with no history no longer gets 300 unwatched titles back.
- **Recommended For You:**
  - Offered to new profiles, based on their favourites or else the library's best-rated titles
    across genres.
  - A failed request is no longer saved as "no recommendations".
  - Its requests run together. On the CI emulator the rows took 154 ms, against 380 ms in the
    previous run (one sample each).

**Tests:**
- `HomeNavigationTest`: 9 remote scenarios.
- `HouseholdTop10Test`: +4 tests.
- `RecommendationColdStartTest`: 3 tests.
- Emulator: Home walked down every row and back up. The rows went `brands → resume → nextup → top10 →
  foryou → 2 × Recently Added` and back the same way, and the profile button only came after the
  banner.

**Not checked on a Fire TV.** Holding the remote's buttons and your own library's rows still need
trying on the TV.

## Performance audit
Measured first, then changed only what the measurements pointed at. The old and new code ran in
alternating rounds of one build on the emulator. See [[Performance Optimization]] for the method and
the full table.

**Changes:**
- **"More Like This":** asked for once per page, after the page's own data; kept 10 minutes, and
  dropped when anything is watched.
- **Seasons:** a season seen on a page comes back instantly. Fixed: switching seasons quickly could
  show the wrong season's episodes.
- **Playing from a film or episode page:** reuses the page's item (one request instead of two or
  three).
- **Home banner:** its Play target is reused until something is watched.

**Results:**
- film pages load in 55 ms instead of 77 ms (median), with 1 request instead of 2;
- show pages use 4 requests instead of 5;
- a season seen before shows in 0 ms with no request;
- 11% fewer requests in a browsing round, and 24% fewer repeats.

Start-up, memory and playback start didn't change measurably. Start-up time on the emulator goes on
drawing frames (software GPU), not app code.

**CI:** the anime resume-and-seek check gives the seek bar 1.5 s to come up before the seek keys
(0.6 s sometimes lost the first seek), and resets the resume point after stopping the app.

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
