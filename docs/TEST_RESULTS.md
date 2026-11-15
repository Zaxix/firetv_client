# Test results

All results below come from the GitHub Actions workflow (`.github/workflows/build.yml`), not from
manual claims. Each run publishes its raw reports and screenshots to the
[`ci-results`](../../../tree/ci-results) branch, and its builds to the
[`latest-build`](../../../releases/tag/latest-build) pre-release.

## 1. Unit and UI tests (every push)

`./gradlew :shared:desktopTest :shared:testDebugUnitTest`: shared tests run on both the JVM/desktop and Android targets.

| Area | Tests |
|---|---|
| Jellyfin authentication | client header format, token stored after sign-in, invalid credentials → friendly error |
| API response handling | unknown/missing fields tolerated, malformed JSON → `BadData`, 404 vs 5xx distinguished |
| Server connection failures | unreachable server tries every candidate URL and reports a friendly error; non-Jellyfin servers rejected |
| Expired authentication | 401 → `SessionExpired`; revoked token at startup deletes the token and asks to sign in again |
| Secure storage | token only in the vault, never in plain files; session restore after relaunch; stable device id |
| Library pagination / search | `startIndex`, `limit`, `parentId`, filters, `searchTerm` sent correctly |
| Playback negotiation | Direct Play uses a static URL with no token in it; transcode URLs made absolute, transcode reasons parsed; server refusals explained |
| HEVC capability → profile | HEVC Main10 device advertises `main\|main 10`, 10-bit, HDR10/HLG ranges, fMP4 HEVC transcoding; H.264-only device never claims HEVC |
| Subtitles | ASS/SSA delivered Embed/External (never Encode) when libass is present; unsupported formats burned in, not dropped |
| Subtitle/audio selection | Default, Always, Forced only, Smart and Off modes; ISO 639-1 ↔ 639-2 matching; external tracks excluded from container ordinals |
| Resume | resume position and progress fraction from `UserData` |
| Theme switching | Dark/Light/System persisted and restored; corrupt settings fall back to defaults; palettes differ |
| Remote / keyboard navigation | arrow keys (same `Key.Direction*` codes as the Fire TV D-pad) move focus across and between rows; Enter activates the focused card |
| Navigation state | back stack keeps screen data and the last-focused card; Back at root doesn't pop; side-nav selection resets the stack |
| Logging | `api_key`, `Token="…"`, `X-Emby-Token`, `AccessToken` and passwords are redacted |
| Artwork | BlurHash average colour, accent extraction (skips near-grey art), placeholder decode |
| Transcoding profile | H.264 is listed first (fast server encodes), HEVC second for stream copy |
| Trickplay | Jellyfin `Trickplay` sheets parsed; the smallest set ≥ 320 px is used; tile URL format |
| Signs & Songs | never the default dialogue track with Japanese audio (Default/Always/Smart); used as the forced track for dubs; title variants recognised, "Sign Language"/"Full" excluded |
| Still watching | Smart threshold scales with episode length (24 min → 90 min, 45 min → 135 min, capped at 4 h); fixed hours; off |
| Subtitle fonts | MKV font attachments recognised by extension, MIME type or codec |
| Next Episode | offered from the credits start (chapters, segments or detection), else the final two minutes |
| Intro/credits detection | recurring chapter lengths across a season → opening/ending; periodic chapters ignored; AniSkip via AniList title search + sequel walk, only for anime, only for missing kinds, title/year must match, Jellyfin token never sent to third parties, nothing sent when the setting is off |
| Dolby Vision routing | per device type (DV TV with/without libdovi, Shield, HDR10 TV with a DV decoder, SDR TV, Windows/mpv): which `VideoRangeType`s go to the server for HEVC/AV1/VP9 and which path each profile takes (native, 7 → 8.1, base layer, server); DV codec strings |
| Dolby Vision bitstream edits | enhancement-layer (NAL 63) removal keeps every other unit byte-for-byte; start-code scan across emulation-prevention bytes; HDR10+ SEI masking changes exactly one byte per message, leaves A/53 captions and suffix SEI alone and walks emulation-prevention bytes; non-Annex-B data untouched |
| Faster starts | preparation fetches item + PlaybackInfo with the resume point and pre-downloads ASS fonts only for transcodes; a prepared result is used once and only for the same title, session, settings and device capabilities; repeated prewarm of the same page does no duplicate requests |
| Brand tiles | studio names matched case/punctuation-insensitively ("Apple TV+", "NETFLIX", "Lucasfilm Ltd."), grouped into brands in catalog order, server studio logo preferred when present |
| Ratings and reviews | MDBList parsing (critics, audience, IMDb + votes, Metacritic, Letterboxd); Trakt comments: spoiler-flagged, spoiler-tagged and one-line comments dropped, reviewer score from either field, private profiles show the username; no keys → server critic score only and no network calls; keys go only to their own service, no Jellyfin credentials leave; session cache and failure back-off; key sync through DisplayPreferences keeps other prefs |
| Ratings UI (rendered) | ratings strip (fresh/rotten tomato, full/empty popcorn, IMDb, Metascore, Letterboxd), review cards with star scores, full-review overlay opened by click and closed by Back; screenshots saved with the test reports (`ui-shots/`) |

**Result: all passing.**

## 2. Real Jellyfin server integration (every push)

CI generates a synthetic library with FFmpeg (`ci/make_samples.sh`), adds original generated artwork
and NFO metadata (`ci/make_artwork.py`), starts `jellyfin/jellyfin:latest` in Docker (Jellyfin
**12.4.0** at the time of writing), and runs `JellyfinIntegrationTest` against it.

| Sample | Contents |
|---|---|
| Harbor Lights | H.264 1080p, AAC, embedded SRT + external `.en.srt` |
| Glass Meridian | **HEVC Main10** 1080p, AC3 5.1, **styled ASS** (colours, `\pos`, karaoke, fades, simultaneous lines) + embedded TTF font |
| Seven Rivers | H.264 MKV with **3 audio tracks** (AAC 2.0 eng, AC3 5.1 jpn, E-AC3 5.1 spa) and **3 subtitle tracks** (ASS, forced SRT, Spanish SRT) |
| Ember Field | **4K HEVC Main10 HDR10** (PQ/BT.2020, mastering display + MaxCLL), E-AC3 5.1 |
| Old Reel | MPEG-4 Part 2 + MP3 in AVI (needs server transcoding on TV) |
| Skylark Isles S01E01–03 | **Anime-style ASS typesetting**: rotated and moving signs, `\clip`, `\t` transforms, karaoke OP romaji, overlapping dialogue, embedded font; HEVC Main10 |
| Prism Coast | **Dolby Vision 8.1** (HDR10 base layer): RPUs from `dovi_tool generate`, muxed by mkvmerge (config record: profile 8, compatibility 1) |
| Neon Tide | **Dolby Vision 5** (no base layer): dovi_tool's profile 5 test RPU on every frame (profile 5, compatibility 0) |
| Velvet Static | **Dolby Vision 7 FEL** (UHD Blu-ray style): dovi_tool's FEL test RPU (profile 7, compatibility 6, EL present) |

| | Check | Result |
|---|---|---|
| ✅ | Server discovery from bare host | found Jellyfin 12.4.0 at http://localhost:8096 |
| ✅ | Invalid credentials rejected | InvalidCredentials |
| ✅ | Authentication + secure token storage | signed in; token only in vault |
| ✅ | Session restore after relaunch | restored |
| ✅ | Expired/invalid token detected | SessionExpired |
| ✅ | Libraries | Movies (movies), TV Shows (tvshows) |
| ✅ | Library pagination | 8 movies over 4 pages of 2, no duplicates |
| ✅ | Search | "meridian" → Glass Meridian |
| ✅ | Metadata + artwork from server | overview, genres, poster served as image/jpeg, BlurHash present |
| ✅ | HEVC Main10 + ASS → Direct Play on HEVC-capable Fire TV profile | hevc Main 10, 10-bit → DirectPlay; ASS delivery = Embed |
| ✅ | HEVC Main10 → transcode on H.264-only device | Transcode; reasons = VideoCodecNotSupported, AudioCodecNotSupported |
| ✅ | ASS kept as styled ASS when transcoding with libass | delivered as `Stream.ass` with `[V4+ Styles]` and `\k` tags intact |
| ✅ | ASS burned in when the device has no ASS renderer | Transcode; ASS delivery = Encode (never flattened to SRT) |
| ⚠️ | 4K HDR10 Direct Play / server tone-mapping decisions | not verifiable on this server: Jellyfin 12.4's probe reported the PQ/BT.2020 sample as SDR (ffprobe in the CI log confirms the file is PQ). The HDR decision logic is covered by `DeviceProfileTest`. |
| ✅ | 4K HDR10 → Direct Play on Windows/mpv | DirectPlay (mpv tone-maps locally) |
| ✅ | Dolby Vision 8.1 (`DOVIWithHDR10`) | DV TV = Direct Play, HDR10 TV = Direct Play (base layer), SDR TV = server converts, Windows = Direct Play |
| ✅ | Dolby Vision 5 (`DOVI`) | DV TV = Direct Play, HDR10 TV = server converts, SDR TV = server converts, Windows = Direct Play |
| ✅ | Dolby Vision 7 FEL (`DOVIWithEL`) | DV TV (profile 8 decoder + libdovi) = Direct Play, HDR10 TV = Direct Play (base layer), SDR TV = server converts, Windows = Direct Play |
| ✅ | Brand tiles from studio metadata | brands = netflix, disney, appletv, paramount, hbo, hulu, natgeo, a24, crunchyroll; Netflix hub → Glass Meridian |
| ✅ | Critic score from server metadata | `CriticRating` 93 → tomatometer 93% |
| ✅ | Rating keys typed on one device reach another | written to the user's DisplayPreferences by one key store, read into another device's vault |
| ✅ | MPEG-4/AVI → transcode on Fire TV, Direct Play on mpv | Fire TV = Transcode (ContainerNotSupported, VideoCodecNotSupported, AudioCodecNotSupported); Windows = DirectPlay |
| ✅ | Multi-track MKV: audio/subtitle selection | 3 audio (aac, ac3, eac3), 3 subs; ja → jpn, forced → English Forced, smart → English Full; DirectPlay |
| ✅ | External SRT sidecar detected | 2 subtitle streams, 1 external |
| ✅ | Direct stream authenticates via header (no token in URL) | HTTP 206 |
| ✅ | Transcode playlist reachable | `#EXTM3U` master playlist |
| ✅ | Resume position + watched state sync | resume at 12 s, listed in Continue Watching; watched toggled on/off |
| ✅ | Favorites | added + removed |
| ✅ | Seasons, episodes and Next Up | 1 season, 3 episodes; Next Up after E1 = S1:E2; following = S1:E3 |
| ✅ | Media segments (Skip Intro) endpoint | 0 segments (no intro-detection plugin on the CI server); handled gracefully |

**Result: 23 passed, 1 not verifiable on this server version, 0 failed.**

## 3. Windows: packaged app self-test (every push)

The MSI/EXE/portable build is packaged on `windows-2022`, then the **packaged** `Sharkfin.exe
--self-test` loads the bundled `libmpv-2.dll` (mpv 0.42) and decodes every sample headlessly.

| Sample | Result |
|---|---|
| Ember Field: HEVC Main10 4K HDR10, E-AC3 | PASS |
| Glass Meridian: HEVC Main10, AC3 5.1, ASS | PASS |
| Harbor Lights: H.264, AAC, SRT | PASS |
| Old Reel: MPEG-4 Part 2 AVI, MP3 | PASS |
| Seven Rivers: 3 audio / 3 subtitle tracks | PASS (forced subtitle rendered: `[FORCED] Sign: "Station"`) |
| Skylark Isles S01E01–E03: HEVC Main10 + anime ASS | PASS |
| Prism Coast: Dolby Vision 8.1 | PASS, `doviProfile=8 colormatrix=dolbyvision` |
| Neon Tide: Dolby Vision 5 | PASS, `doviProfile=5 colormatrix=dolbyvision` |
| Velvet Static: Dolby Vision 7 FEL | PASS, `doviProfile=7 colormatrix=dolbyvision` |

`colormatrix=dolbyvision` means mpv picked up the RPU metadata and hands it to libplacebo, which
applies the Dolby Vision reshaping when rendering with gpu-next (the self-test decodes with `vo=null`,
so the reshaped picture itself is checked on real hardware only).

The runner has no GPU, so decoding was software (`hwdec=no`); D3D11VA/NVDEC hardware decoding needs
real hardware. The first run found that libmpv requires `vulkan-1.dll`, which PCs without GPU drivers
lack, so the Khronos Vulkan loader is now bundled.

A launch smoke test then starts the installed-layout app, confirms it stays running and captures a
screenshot. A best-effort step signs in to the public Jellyfin demo server and captures Home and the player:
real video plays through mpv in its own window, with Sharkfin's transparent controls drawn on top.

## 4. Android TV emulator (every push)

`reactivecircus/android-emulator-runner` boots an Android 11 TV image (1080p), installs the debug APK,
and drives it only with remote key events (`adb shell input keyevent`) against the Docker Jellyfin
server. Debug builds log every remote key the activity receives, so the run proves the D-pad reaches the app.

* typed the server address and pressed Enter → login; typed credentials → Home ✅
* **D-pad Down** moved focus from the hero into the first rail; **Right + Select** opened that movie's
  detail page; **Back** returned Home with focus restored to the same card; **Back** again opened the
  sidebar; **Down + Select** in the sidebar switched to Up Next ✅
* **D-pad Down ×4** in Settings reached the new Ratings page, and once more the About page, which lists the detected decoders and
  capabilities (Media3 1.8.0, software H.264/HEVC on the emulator, Keystore token storage) ✅
* played video through Media3. The emulator's decoders were probed (software, ≤1080p) and the
  app chose **Direct Play** for H.264 MKV and asked the server to **transcode** HEVC Main10, with
  reasons `VideoCodecNotSupported, VideoProfileNotSupported, VideoBitDepthNotSupported` ✅
* **libass rendered the anime typesetting sample on Android**: rotated sign, `\move`, `\clip` + `\t`
  transform, karaoke romaji and overlapping dialogue, plus colours, `\pos` and simultaneous top/bottom
  lines in the styled sample (see screenshots) ✅
* player controls (progress bar, elapsed/remaining, transport, audio/subtitle/info, next episode) ✅
* **Play from a detail page** with the remote: playback had been prepared while the page was open,
  so the engine started **47 ms** after Select, against 650–900 ms for the same server when opened cold ✅
* no crashes (`FATAL EXCEPTION` check on logcat) ✅; every ExoPlayer playback above already runs
  through the new Dolby Vision-aware video renderer. The emulator reports no DV decoder and no HDR
  display (`Dolby Vision: display=false decoderProfiles=[]` in the log), so the DV-specific paths
  themselves (native, 7 → 8.1, HDR10+ masking) can't run there
* **MPV player** (switched with the `player` launch extra, same as the setting): the anime typesetting
  episode and the HEVC Main10 movie through server transcoding, and the multi-track MKV by Direct Play.
  Rotated/moving signs, `\clip` + `\t`, karaoke and fades rendered by mpv's libass; the server's font
  attachment was downloaded and used, matching ExoPlayer pixel-for-pixel in typeface ✅
* **Server font attachments for transcodes**: both engines now use the MKV's embedded font when the
  server transcodes (ExoPlayer previously fell back to the system font) ✅
* **R8-shrunk release APK** (with libmpv, FFmpeg and libdovi): fresh install, sign-in with the remote, Home, then playback of
  the anime ASS episode and the HEVC Main10 movie through Media3 + libass + FFmpeg, then the HEVC movie
  again through libmpv. The run fails on
  any crash, missing class or native-library error, or if the player never reaches the READY state ✅

The emulator runs found and fixed three real bugs:

1. **The on-screen keyboard stayed active after sign-in and swallowed the remote's D-pad** (Select
   typed "q"). Sharkfin now dismisses the keyboard on connect, on sign-in and when the main app opens.
2. Android TV's default pivot scrolling clipped the top of Home; replaced with minimal scroll-into-view.
3. The shared arrow-key handler is now desktop-only; Android uses its native D-pad focus search.

The emulator occasionally fails to boot in CI (an infrastructure timeout, not an app failure);
re-running the job fixes it.

## Not tested in CI (needs real hardware)

Fire TV hardware decoders (HEVC/AV1/DV) and Dolby Vision output (native, profile 7 → 8.1 conversion,
HDR10+ masking), live MDBList/Trakt calls with real keys, HDMI audio passthrough, HDR output on a TV, libass overlay
performance on low-end sticks, Windows GPU decoding and HDR output, Xbox controllers. See
[Known limitations](KNOWN_LIMITATIONS.md).
