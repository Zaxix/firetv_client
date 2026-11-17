---
tags: [development, changelog]
---
# Change Log

Newest first. Numbers come from CI (Android TV emulator + a real Jellyfin server in Docker), not
from a real Fire TV.

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
| Metric (CI emulator) | Before | After |
|---|---|---|
| Home: first 4 rows filled from the server | see final report | see final report |
| Home: server requests in first 10 s (api / images / media) | see final report | see final report |
| Direct-play resume (cold / from details) | 2.9–3.0 s / 2.0 s | unchanged (kept) |

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
