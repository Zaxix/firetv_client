---
tags: [feature, player, navigation]
---
# Disc Menus

> [!warning] Status: written and compiled on CI, **not yet run on a Fire TV.** Everything under
> "Unverified" below is a guess until it has been tried on the device.

## What it does
Pressing **Play** on a film's page opens the film's DVD-style menu instead of starting the video:
an animated main menu (sparkles over a looping background, music), a transition into each
submenu, scene selection, cast and crew, languages / subtitles, and a "Play Movie" letter
animation. The menu then hands off to Sharkfin's normal player. Films with no menu pack behave
exactly as before.

The first and only pack is **Harry Potter and the Philosopher's Stone (UK, disc 1)**, rebuilt from
two recordings of the disc (a menu walkthrough and the full disc menu including the cloud intro).

The menu is only used when **all** of these hold: the item is a Movie, it has no resume position,
the user didn't choose "Start from beginning", **Settings → Disc menus** is on (the row only shows
when a pack is installed), and a pack matches the film. A film with a resume position goes straight
back into the player.

## How it works
- **Pack, not code.** A menu is a folder (`pack.json` schema 2 plus media) read from
  `Android/data/<appId>/files/discmenus/<pack>/` on the device. It is not part of the public
  repository or the APK, because it is the disc's own video, music and artwork. It gets onto the
  device in one of two ways: copied there by hand (`adb push`; debug builds use the `.debug`
  application id), or downloaded by the app as described next.
- **Downloading** (Settings → Disc menus → source address, plus an optional read-only token for a
  private host). **Built-in token (Nov 2026):** builds can carry a default token for every install,
  taken from the `DISC_MENU_TOKEN` repository secret at build time (never in the source or the logs;
  `androidApp/build.gradle.kts` → manifest meta-data → `BuildValues` → `Platform.builtInDiscMenuToken`).
  It's used only when no token is entered in Settings, and only for `https://` GitHub addresses
  (`isGitHubAddress`: github.com, api.github.com, *.githubusercontent.com), so it can't be sent to
  another site. It's never shown or saved in Settings; the field's label says when it's in use.
  Like anything in an APK it can be extracted by whoever has the APK: keep it read-only and limited
  to the menu repository. Replace it by updating the secret; the next build carries the new one. With no address set, the Jellyfin server itself is the source, at
  `<server>/web/discmenus`: put `catalog.json` and the pack folders in a `discmenus` folder inside
  the server's web folder (untested: whether the server serves every file type from there). The source is a folder of static files: `catalog.json` (which films have a pack,
  matched by TMDB / IMDb id), then `<id>/manifest.json` and the pack's files. Pressing Play on a
  matching film opens the menu at once. The app fetches `pack.json` and the intro first (about
  1.2 MB for the first pack), and the intro plays while the rest arrives, file by file, each one
  checked by size and SHA-256 and only given its final name when it matches. Files carry a
  stage: 0 = `pack.json` + intro, 1 = loop, music, sparkle frames (3.3 MB), 2 = transition, page art
  and highlights (1.8 MB), 3 = the Play Movie letter. The menu opens when stage 1 is on the device
  (the intro holds its last picture until then, and shows "Downloading menu… NN%"), a submenu
  opens when stage 2 is, Play Movie when stage 3 is; before that the button says it is still
  downloading. The loop and music players are only created once their files are there. If a file
  keeps failing (three tries), OK plays the film anyway and Back leaves. If the menu can't open at
  all (source unreachable and no saved file list, no pack), the message screen offers the same:
  OK plays the film, Back returns. An interrupted download
  carries on from the last finished file. The token is sent only to the source's own host.
  `tools/disc-menu/make_manifest.py` writes the manifest and catalog for a pack folder.
- **Leaving.** A pack the app downloaded is deleted when the menu is left (Back from the main
  menu, or Back from a failure message), so the next Play downloads it again. Opening the film
  from the menu does not delete it, so Back from the player returns to the menu; if the app was
  closed in between, the next start removes it. Settings → Disc menus → "Keep downloaded menus"
  turns the deleting off. Packs copied by hand never carry the app's marker and are never deleted.
- **Matching.** By TMDB / IMDb id first. A title pattern is used only for items that carry no
  provider ids at all, so a different film with a similar name is never matched by accident.
- **State machine** (`MenuLogic`): pure Kotlin, time in milliseconds, effects out (start the intro,
  start the loop, play a chapter, …). Unit-testable without a screen. Keys: arrows, Select, Back.
  Select or Back skips the intro and any transition; Back on a submenu goes up one level; Back on
  the main menu leaves to the film page.
- **Screen** (`DiscMenuScreen`): a 1920×1080 canvas, letterboxed, mouse and D-pad.
- **Media** (`ExoDiscMenuMedia`, Media3): three players: the background loop, one shared player for
  the intro / transition / letter, and the music. Sparkle highlights are 1366 small WebP frames
  drawn additively on top.
- **Scene selection:** each thumbnail slot maps to chapter *n* of the film. A slot the server has
  no chapter for is skipped by the arrows and refused when clicked. The disc has 35 scenes; the
  server's chapter list can differ.
- **Cast and crew** come from the server's people list for the film (names and roles), drawn over
  clean copies of the credits backdrops at the measured line pitch. A page the server has nothing
  for keeps the disc's own printed names.
- **Hand-off to playback.** Chapters, "Play Movie", "End credits" and the subtitle choice become a
  normal playback request (`Route.Player` with a start position and optional subtitle override),
  so progress reporting, audio / subtitle preferences and the existing player are unchanged.
- **"Off the device".** Leaving the menu stops and releases the players and drops the decoded
  images (`release()` from a `DisposableEffect`), and a downloaded pack is then deleted (see
  Leaving). The menu keeps no media in memory once it is closed.

## Relevant files
`shared/src/commonMain/kotlin/com/auroratv/discmenu/` — `PackModel.kt`, `MenuLogic.kt`,
`MenuGeometry.kt`, `DiscMenuScreen.kt`, `DiscMenuRepository.kt`, `SpriteBank.kt`, `PageArtStore.kt`,
`PackCheck.kt`, `DiscSubtitles.kt`, `CreditsLayout.kt`, `DiscMenuPlatform.kt`.
Android side: `shared/src/androidMain/kotlin/com/auroratv/android/AndroidDiscMenu.kt`.
Changed existing files: `Detail.kt` (Play routing), `Navigator.kt` (`Route.DiscMenu`, start
position on `Route.Player`), `PlaybackPreparer.kt` / `PlayerController.kt` (start position,
subtitle override), `App.kt` (full-screen routes), `Settings.kt`.
Tests: `MenuLogicTest.kt` (state machine, navigation, credits layout), `ShippedPackTest.kt`
(the real pack's data: every button reachable with the arrows, no overlapping buttons, 35 chapter
slots, targets exist).

## Dependencies
Media3 / ExoPlayer (already in the app). No new libraries.

## Data storage
Nothing is stored by the menu except the Settings toggle. The pack is read-only files.

## Performance considerations
Measured on the build machine (not a Fire TV):
- **Pack size on disk: 6.4 MB** (was about 20 MB; the first recreation was a 171 MB Electron zip).
  Loop 0.57 MB, transition 0.41 MB, intro 1.15 MB, letter 0.10 MB, sparkle frames 2.19 MB, page plates
  1.27 MB, page highlight crops 0.14 MB (one `page_sprites.bundle` file, 52 members; the pack is 24 files instead of 75, because
  many tiny requests were what made the last 30% slow), music 0.49 MB. What the menu waits for
  (stage 0 + 1) is 4.5 MB, was 15.2 MB. Everything is encoded on the build machine; the host serves
  static files only.
- **Video is HEVC (Main, 8-bit)** since the author allowed it: loop and transition 1920×1080 at
  40.84 fps (5350/131), intro and letter 640×360 at 30 fps, frame counts and durations identical to
  the H.264 versions (828 / 300 / 1276 / 81 frames). Measured against the previous files: SSIM
  0.989 (loop), 0.996 (transition), 0.990 (intro), 0.986 (letter). A device without an HEVC
  decoder cannot play these; this has not been tried on any Fire TV.
- **Sparkle frames** are WebP q85 (was q95): PSNR 42.6–44.6 dB against the source PNGs; rectangles,
  frame counts and fps unchanged. **Plates** q84 (SSIM 0.98 against the previous plates).
- **Music is Opus** (48 kHz stereo, 96 kbps, 0.49 MB, same 39.8065 s) rather than FLAC, since Opus
  decodes everywhere Media3 does.
- Only four decoded page backdrops are kept (LRU); sparkle frames are decoded a few ahead of the
  playhead, never all at once.
- Not measured: CPU, memory and frame pacing on a Fire TV with three decoders open. This is the
  first thing to check ([[Testing Guide]]).

## User interactions
D-pad or mouse. Up / Down / Left / Right move between buttons; Select activates; Back goes up one
level. Next / previous page keys walk the scene pages (1–6) and the cast / crew pages.

## Known limitations
Unverified — listed so they get checked, not assumed:
- **Downloading has never been run against a real host or on a device.** Unit tests cover the
  manifest rules, stage order, resume, retry and failure, and the menu's waiting; the network and
  file code (`AndroidPackStore`) is only compiled. Whether 4.5 MB (stage 0 + 1) arrives within the
  42-second intro depends on the connection; if not, the intro holds its last picture.
- GitHub raw files of a private repository with `Authorization: token <t>` is the intended
  private host; that exact request has not been tried.
- The pack's host is not in this repository and I did not upload it: the repository is public and
  the pack is the disc's own material.
- Additive drawing of the sparkles **over a video surface** (TextureView): never seen on a device.
- Gapless looping of the background and music playlists on Fire TV.
- Three decoders at once on a low-end Fire TV; the intro is only 640×360, so it is soft.
- The intro is from the US *Sorcerer's Stone* edition (the only complete recording available), so
  it differs from the UK disc's cloud sequence; a 400 ms cross-fade hides the hand-off.
- Not observed in either recording, so assumed: "End credits" → last chapter; which subtitle code
  each language button selects; where Back goes from each page; the highlight look of buttons the
  recording never selected (drawn as plain outlines); how long the Play Movie letter holds
  before playback starts; what the ◂ arrow on the pages does.
- Hotspot padding on the main menu (14 px) is an assumption.
- Scene count: the disc shows 35 scenes; a server with a different chapter count gets a
  "not available" message on the missing ones.

## Related notes
[[Video Player]] · [[Navigation System]] · [[Movies and TV Shows]] · [[Subtitles]] · [[Testing Guide]] ·
[[Change Log]]
