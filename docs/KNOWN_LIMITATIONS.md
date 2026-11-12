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
* **Minimum Android version is 6.0 (API 23).** That covers Fire OS 6, 7 and 8 devices. Fire TV devices
  still on Fire OS 5 (Android 5.1) aren't supported.
* **Release signing:** without the `AURORA_KEYSTORE_*` repository secrets, CI signs each release APK
  with a new throwaway key, so installing a newer build over an older one fails until you uninstall the
  old one. Add the secrets (see README) for upgrade-in-place.
* **No R8 shrinking** in release builds (deliberate, to protect JNI-heavy libraries), so the APK is
  larger than strictly necessary.

## Windows

* **Video window composition:** mpv renders into its own child window, and the controls are a
  transparent dialog on top. This is the standard approach, but on unusual setups (some screen
  recorders, remote desktop) the overlay may not composite. Fullscreen (F11) is the intended TV mode.
* **Audio passthrough** must be enabled manually (Settings → Playback): Windows can't reliably report
  what a receiver connected over S/PDIF/HDMI accepts.
* **Controllers:** XInput (Xbox-compatible) controllers are supported. DirectInput-only and
  PlayStation pads need Steam Input or DS4Windows to present as XInput.
* **The installers aren't code-signed**, so Windows SmartScreen will warn on first run ("More info →
  Run anyway"). Signing needs a paid certificate.
* Built for **x64 only** (no ARM64 Windows build).

## Server features

* **Skip Intro** needs Jellyfin 10.10+ media segments, produced by a segment provider plugin (e.g.
  *Intro Skipper*). Without one, no Skip button appears.
* Requires **Jellyfin 10.9 or newer** (uses the 10.9+ routes such as `/UserViews`, `/UserItems/Resume`,
  `/UserFavoriteItems`).
* Live TV, music libraries, photos, SyncPlay and Quick Connect aren't implemented; Aurora focuses on
  movies and TV.
