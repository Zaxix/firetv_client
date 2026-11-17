# Architecture

Sharkfin is a Fire TV / Android TV app written as a Kotlin Multiplatform codebase. Nearly everything —
the Jellyfin client, state, the UI and the player controls — is in the shared module. Only the parts
that have to touch the platform are Android-specific: video decoding and rendering, secure storage,
and capability probing. The shared module also compiles for the plain JVM, which is used only as a
fast host for the tests (the Windows desktop app that used it was removed).

```
┌────────────────────────── shared (Kotlin Multiplatform) ──────────────────────────┐
│ ui/            Compose Multiplatform screens, theme, focus system, player chrome   │
│ ui/nav         Back stack with per-entry state + focus memory                      │
│ playback/      DeviceCapabilities → DeviceProfile → PlaybackInfo negotiation,      │
│                PlayerController (resume, tracks, skip intro, next up, reporting)   │
│ data/          SessionManager: servers, users, token vault, session restore        │
│ jellyfin/      Typed Ktor client for the Jellyfin REST API, image URL builder      │
│ settings/      Settings model + JSON persistence                                   │
│ core/          Errors with user-facing messages, token-redacting logger           │
├──────────────── androidMain ─────────────────┬──────── jvmMain (tests only) ───────┤
│ ExoPlayerEngine: Media3 + MediaCodec (HW)    │ OkHttp engine, image decoding and   │
│   + Jellyfin FFmpeg audio extension          │   D-pad flag for the JVM test host  │
│   + libass (ass-media) OVERLAY_OPEN_GL       │                                     │
│ MpvAndroidEngine: libmpv (optional)          │                                     │
│ AndroidCapabilities: MediaCodecList, HDR     │                                     │
│   display caps, HDMI passthrough             │                                     │
│ KeystoreVault: AES-256-GCM, Android Keystore │                                     │
└──────────────────────────────────────────────┴─────────────────────────────────────┘
        androidApp (APK/AAB, Fire TV launcher)
```

## Why this stack

| Requirement | Choice | Reason |
|---|---|---|
| Shared UI, testable off-device | Compose Multiplatform | One set of screens; D-pad, keyboard and mouse share one focus system; UI tests run on the JVM |
| Native, hardware-accelerated playback | Media3 (or libmpv, optional) | The best-maintained hardware-decoding engine on Android; no HTML5 video |
| ASS/SSA with full styling | libass in both players | ExoPlayer's built-in SSA support drops positioning, karaoke and animation; libass is the reference renderer |
| Networking | Ktor + kotlinx.serialization | Multiplatform, cancellable, typed DTOs |
| Local cache | Coil 3 memory + disk cache for artwork; JSON files for settings/servers | The app's own data is tiny (settings, server list), so SQLDelight would add a code generator and schema migrations without a measurable gain. Library data is always fetched fresh from the server (the spec requires server-sourced data), and back-stack entries keep screen data in memory so returning is instant. |

## Ratings and reviews

`RatingsService` (common) takes the server's `CriticRating` as the critics score and, with optional
keys, adds MDBList (`/tmdb/{movie|show}/{id}`: Rotten Tomatoes critics + audience, IMDb, Metacritic,
Letterboxd) and Trakt comments (`/{movies|shows}/{imdb id}/comments/likes`, spoiler-flagged and
spoiler-tagged ones dropped, the reviewer's own 1–10 score kept). The detail page fetches them after
it is on screen; results are cached for the session (failures for five minutes), each call has an
eight-second budget, and keys go only to their own service and are never logged. Keys live in the
secure vault and are mirrored into the user's Jellyfin DisplayPreferences (`aurora-ratings`) so a
key typed on a PC appears on the TV.

## Playback flow

1. **Probe** (`AndroidCapabilities`), once per launch:
   which decoders exist and whether they're hardware, maximum resolution and bit depth (HEVC Main10, VP9
   Profile 2, AV1 Main10), the display's HDR modes, PCM-decodable audio codecs (platform decoders plus
   the FFmpeg extension), and which bitstream formats the HDMI sink accepts for passthrough.
2. **Profile** (`DeviceProfileBuilder`): turns those facts into a Jellyfin `DeviceProfile`: Direct Play
   containers/codecs, per-codec limits (`Width`, `Height`, `VideoBitDepth`, `VideoProfile`,
   `VideoRangeType`), an HLS transcoding profile (H.264 first, because the server encodes to the first
   codec listed and HEVC encodes are several times slower on typical home servers; HEVC is listed second
   over fMP4 when the device decodes it in hardware, so an HEVC source that only needs new audio is
   copied rather than re-encoded), and subtitle delivery (Embed/External for what libass/Media3 render, Encode
   for anything else, so unsupported subtitles get burned in rather than dropped).
3. **Negotiate** (`PlaybackNegotiator`): `POST /Items/{id}/PlaybackInfo` with the profile, start
   position and chosen streams. The server answers Direct Play (static file URL), Direct Stream
   (remux) or Transcode (HLS URL plus reasons). The token goes in request headers, never in URLs Sharkfin builds.
4. **Play** (`PlayerController` → `PlaybackEngine`): loads the URL, selects the audio/subtitle track by
   container ordinal (embedded) or registers sidecar files (external), seeks to the resume point, and
   reports start, progress (every 10 s and on pause) and stop to Jellyfin.
5. **Switching tracks**: switching between embedded tracks during Direct Play happens in the engine
   with no reload. Choosing a subtitle that needs burning in, or changing audio while transcoding,
   asks the server for a new stream at the current position.
6. **Dolby Vision routing** (Android, ExoPlayer): `DolbyVisionRenderersFactory` replaces Media3's
   video renderer with one that rewrites each DV track's `Format` before decoder selection, using the
   per-profile decision in `DolbyVisionSupport.routeFor` (the same rules `allowedRangeTypes` gives the
   server): native DV, profile 7 → 8.1 (codec string rewritten so the profile 8 decoder is chosen, and
   a codec-adapter filter that drops NAL 63 and runs libdovi on NAL 62), or the plain HEVC/AVC/AV1
   decoder for the base layer. The filter copies each access unit into a reused heap buffer once,
   edits it there and writes back only when something changed; HDR10+ SEI masking for affected Fire TV
   models runs in the same pass.
7. **Buffering**: Media3 buffers up to 2 minutes / 160 MB ahead (starting after 1.5 s) and keeps 15 s
   behind the playhead; mpv keeps a 400 MiB forward and 100 MiB back cache and resumes after 2 s of
   cache instead of stalling longer. Seeks jump to the nearest keyframe on both engines so scrubbing
   is near-instant.
7. **Player UI cost**: engines publish position at most ~4 times a second, and only the timeline and
   clocks read it. Everything else in the player overlay reads `derivedStateOf` values that change
   rarely (playing, buffering, active intro/credits range), so with the controls hidden the overlay
   doesn't redraw at all while video plays.
8. **Scrubbing**: D-pad (hold to accelerate; commits 0.45 s after the last press), mouse click, drag
   and hover. When the server has trickplay sheets (Jellyfin 10.9+), the bubble shows a thumbnail:
   each sprite sheet is decoded once and the frame is picked with an offset, so moving within a sheet
   costs no network or decode work.
9. **Skip markers** (`SkipDetector`): server media segments → chapter names → detection, merged per
   kind so detection only fills what the server lacks. Detection first looks for an unnamed chapter
   whose length (±1.5 s) recurs in other episodes of the season near the start (opening) or end
   (ending), ignoring files with periodic chapters. For anime it then asks AniSkip by MyAnimeList id
   (from provider ids, or AniList: title search checked against the series title/year, then SEQUEL
   relations for later seasons and split cours). It runs after playback starts, with an 8 s real-time
   timeout, over a plain HTTP client that never carries the Jellyfin token.
10. **Next Episode** is offered from the start of the credits range (or the final two minutes when
   there's no credits marker), not throughout the episode.
11. **Errors**: an engine error offers "Try with transcoding", which renegotiates with Direct Play
   disabled. That's the right fallback for decoder or container failures.

## UI and focus

* Every focusable element goes through `FocusSurface`, which gives the same focus lift, ring and
  shadow for D-pad, keyboard and mouse hover. Scale changes go through `graphicsLayer`, so focus
  never triggers relayout.
* `Modifier.arrowKeyNavigation()` moves focus with arrow keys where the platform doesn't (the JVM
  test host); Android does this natively for the D-pad.
* Each back-stack entry keeps its screen model (data, `LazyListState`) and a `FocusMemory`, so Back
  returns to the exact card that was focused.
* Rails use `focusRestorer` so moving up/down into a rail returns to its last-focused card.
* Home rails load lazily: a rail fetches its items when it first scrolls into view.
* Artwork is requested at the card's real pixel size on this display (density × size × focus zoom,
  rounded to 64 px steps so layouts share cache entries) via Jellyfin's `fillWidth`, and decoded at
  that size, so focused cards stay sharp. Backdrops are requested at the window's pixel width, up to
  3840. BlurHash gives an instant placeholder and the artwork's accent colour without downloading the
  image first.

## Security

* Access tokens are stored only in the Android Keystore (AES-GCM);
  `servers.json` holds no secrets (a test asserts this).
* `Log.redact` strips `api_key`, `Token="…"`, `X-Emby-Token`, `AccessToken` and passwords from every
  log line before it reaches logcat.
* Android allows cleartext HTTP (most home servers are LAN HTTP) and user-installed CAs (for
  self-signed certificates). `allowBackup` is off.
