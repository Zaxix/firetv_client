---
tags: [development, changelog]
---
# Change Log

Newest first. Numbers come from CI (Android TV emulator + a real Jellyfin server in Docker), not
from a real Fire TV.

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
