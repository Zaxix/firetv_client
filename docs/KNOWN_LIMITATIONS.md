# Known limitations

These are stated plainly rather than papered over. Items marked *hardware* depend on the device and
can only be confirmed on that device; CI can't test them.

## Verified in CI vs. needs real hardware

| Area | Verified in CI | Needs testing on your hardware |
|---|---|---|
| Builds | Debug/release APK, AAB, MSI, EXE, portable ZIP | — |
| Jellyfin API, auth, paging, search, favorites, resume, Next Up | ✅ against a real Jellyfin server in Docker | — |
| Playback negotiation (Direct Play / transcode decisions) | ✅ against the real server for HEVC Main10, 4K HDR10, multi-track, AVI/MPEG-4, ASS | — |
| ASS kept as ASS when transcoding; burned in when unsupported | ✅ against the real server | — |
| libmpv decoding + libass in the packaged Windows app | ✅ headless self-test on a Windows runner (software decode — runners have no GPU) | D3D11 hardware decode and HDR output on a real GPU/display |
| Desktop UI rendering | ✅ screenshots from the real build on Linux/Xvfb + a Windows launch screenshot | — |
| Fire TV remote navigation, Media3 HW decoding, libass overlay, HDMI passthrough, HDR | Shared focus logic is unit-tested with D-pad key events | **Everything else on a real Fire TV** |

## Android / Fire TV

* **Subtitle delay isn't available on Android.** Media3's subtitle pipeline has no timing offset, and
  libass rendering follows the player clock. The control is hidden on Android rather than shown
  broken; Windows supports it fully. Workaround: fix timing on the server (Jellyfin's subtitle editor
  plugins) or play on Windows.
* **Fonts for ASS while transcoding:** when the server transcodes video, the `.ass` file is delivered
  separately, so fonts embedded in the MKV aren't available and libass falls back to system fonts.
  Direct Play (the normal case on HEVC-capable Fire TVs) uses the embedded fonts.
* **ASS rendering cost:** full libass overlay rendering on very old/low-end sticks (1st-gen Fire TV
  Stick, Fire OS 5) can drop frames on heavy typesetting. Settings → Subtitles → *ASS/SSA rendering:
  Simple* switches to static cue rendering. Rendering is also capped at 1080p on 4K TVs.
* **Dolby Vision profile 5** (no HDR10 base layer) plays only on devices with a DV decoder and a DV
  display; otherwise the server tone-maps it, which needs a server with tone-mapping configured.
* **Dolby Vision profile 7 → 8.1 conversion** drops the full enhancement layer (FEL), as every
  conversion-based player does: the picture is the base layer shaped by the converted RPU, which is
  how Fire TVs and most TVs show UHD Blu-ray Dolby Vision at all. The conversion needs libdovi, which
  ships for arm64-v8a, armeabi-v7a and x86_64; on other ABIs profile 7 plays as HDR10. The routing and
  the bitstream edits are unit-tested and the server decisions are tested against a real Jellyfin with
  generated profile 5 / 7 / 8.1 files, but **no Dolby Vision playback has been checked on a real Fire TV
  or DV TV yet** (CI has neither).
* **Ratings and reviews** beyond the server's critic score need free keys (Settings → Ratings):
  MDBList for Rotten Tomatoes critics/audience, IMDb, Metacritic and Letterboxd; Trakt for reviews.
  IMDb's user reviews aren't available: IMDb offers no API for them and its terms don't allow scraping,
  so reviews come from Trakt, where spoiler-flagged ones are filtered out. Episodes show the server's
  own ratings only. The CI server has no keys, so the live MDBList/Trakt calls are only covered by
  mocked tests.
* **Minimum Android version is 6.0 (API 23).** That covers Fire OS 6, 7 and 8 devices. Fire TV devices
  still on Fire OS 5 (Android 5.1) aren't supported.
* **Release signing:** releases are signed with a permanent key (see README). Builds from before it
  existed used throwaway keys, so uninstall such a build once; after that, updates install in place.
  The debug APK is still signed with a per-build key.
* **MPV on Android TV** is opt-in, needs Android 8 / Fire OS 7 or newer (libmpv's minimum; older
  devices only see ExoPlayer), and is newer than the ExoPlayer path. Video decoding is limited to what
  the device's MediaCodec decodes in hardware (the same as ExoPlayer), because software HEVC/4K is too
  slow on TV boxes. Its renderer (gpu-next on OpenGL ES 3) has been tested on the Android TV emulator
  only; if video stays black on a device, switch back to ExoPlayer. The first time MPV shows subtitles
  it indexes the system fonts (a few seconds, once). Bundling libmpv makes the APK considerably larger.
* **Release builds are shrunk with R8.** Native-backed libraries (libass, FFmpeg) and Sharkfin's own
  classes are kept explicitly, and CI installs and plays the release APK on an emulator. If a release
  build ever misbehaves where the debug build doesn't, the debug APK is the fallback.
* **Scrub thumbnails need trickplay on the server.** Turn on "Enable trickplay image extraction" in
  the library settings (Jellyfin 10.9 or newer); without it, scrubbing shows only the time.

## Windows

* **Video window composition:** mpv renders into its own child window, and the controls are a
  transparent dialog on top. This is the standard approach, but on unusual setups (some screen
  recorders, remote desktop) the overlay may not composite. Fullscreen (F11) is the intended TV mode.
* **Audio passthrough** must be enabled manually (Settings → Playback): Windows can't reliably report
  what a receiver connected over S/PDIF/HDMI accepts.
* **libmpv needs `vulkan-1.dll`.** It ships with every GPU driver, and Sharkfin bundles the Khronos loader
  as well, so the app also starts on PCs or VMs without GPU drivers (playback then uses software decoding).
* **Controllers:** XInput (Xbox-compatible) controllers are supported. DirectInput-only and
  PlayStation pads need Steam Input or DS4Windows to present as XInput.
* **The installers aren't code-signed**, so Windows SmartScreen will warn on first run ("More info →
  Run anyway"). Signing needs a paid certificate.
* Built for **x64 only** (no ARM64 Windows build).

## Server features

* **HDR detection is the server's job.** Jellyfin decides HDR10/HLG/Dolby Vision from its own media
  probe. On the CI server (Jellyfin 12.4) a correctly tagged PQ/BT.2020 test file was reported as SDR,
  so in that case the server would neither tone-map nor flag it as HDR. If HDR titles look washed out,
  refresh their metadata on the server.

* **Intro/credits markers, in order of reliability:** server media segments (Jellyfin 10.10+ with a
  provider plugin; *Intro Skipper* fingerprints audio and covers every TV show, so it's the best
  option), then chapter names, then Sharkfin's own detection. The detection only works when (a) the
  files have chapters and an opening/ending chapter of the same length recurs in other episodes of
  the season, or (b) the show is anime that AniSkip knows. AniSkip times are crowd-sourced, and
  mapping a Jellyfin season onto a MyAnimeList entry (via provider ids, or an AniList title search
  that must match the show's title or year) can still pick the wrong entry for unusually split
  seasons, so markers can occasionally be off. Online lookups send only the show title or MAL id and
  the episode number; they can be turned off in Settings → Playback.
* Requires **Jellyfin 10.9 or newer** (uses the 10.9+ routes such as `/UserViews`, `/UserItems/Resume`,
  `/UserFavoriteItems`).
* Live TV, music libraries, photos, SyncPlay and Quick Connect aren't implemented; Sharkfin focuses on
  movies and TV.

* **In-app updates** need one confirmation on Android/Fire TV per update: sideloaded apps can't install
  silently, so Android's install screen always appears. On Fire OS the first update also asks you to
  allow Sharkfin under *Install unknown apps*. Debug APKs, the Windows portable ZIP and Linux builds
  don't install updates themselves (they open the download page). The update flow is unit-tested
  with a fake platform; the real Android install screen and the Windows MSI upgrade have not been
  exercised by CI, which can't confirm an install.
* **Remembered tracks** are stored on each device, not synced between devices.

## Profiles

* **Device-only profiles** (added with "Add profile" without signing in another account) keep their
  watch history, Continue Watching, Next Up and favourites on this device only; they don't appear on
  other devices or in other Jellyfin apps, and the server's dashboard shows their playback under the
  account they were added to. Thumbs up/down and personal star ratings stay the account's. Removing
  the account from the device also removes the profiles added under it.
