# Architecture

Aurora TV is one Kotlin Multiplatform codebase with two thin native shells. Nearly everything — the
Jellyfin client, state, the UI and the player controls — is shared. Only the parts that have to
touch the platform are native: video decoding and rendering, secure storage, and capability probing.

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
├──────────────── androidMain ─────────────────┬──────────── desktopMain ────────────┤
│ ExoPlayerEngine: Media3 + MediaCodec (HW)    │ MpvEngine: libmpv via JNA,          │
│   + Jellyfin FFmpeg audio extension          │   gpu-next/D3D11, hwdec=auto-safe   │
│   + libass (ass-media) OVERLAY_OPEN_GL       │   + libass built into mpv           │
│ AndroidCapabilities: MediaCodecList, HDR     │ DesktopPlatform: two-window player  │
│   display caps, HDMI passthrough             │   host, DPAPI vault, file store     │
│ KeystoreVault: AES-256-GCM, Android Keystore │ GamepadBridge (desktopApp): XInput  │
└──────────────────────────────────────────────┴─────────────────────────────────────┘
        androidApp (APK/AAB, Fire TV launcher)          desktopApp (jpackage MSI/EXE)
```

## Why this stack

| Requirement | Choice | Reason |
|---|---|---|
| Shared UI on TV and PC | Compose Multiplatform | One set of screens; D-pad, keyboard and mouse share one focus system |
| Native, hardware-accelerated playback | Media3 on Android, libmpv on Windows | The best-maintained hardware-decoding engine on each platform; no HTML5 video |
| ASS/SSA with full styling | libass on both | ExoPlayer's built-in SSA support drops positioning, karaoke and animation; libass is the reference renderer |
| Networking | Ktor + kotlinx.serialization | Multiplatform, cancellable, typed DTOs |
| Local cache | Coil 3 memory + disk cache for artwork; JSON files for settings/servers | The app's own data is tiny (settings, server list), so SQLDelight would add a code generator and schema migrations without a measurable gain. Library data is always fetched fresh from the server (the spec requires server-sourced data), and back-stack entries keep screen data in memory so returning is instant. |

## Playback flow

1. **Probe** (`AndroidCapabilities` / `DesktopPlatform.probeCapabilities`), once per launch:
   which decoders exist and whether they're hardware, maximum resolution and bit depth (HEVC Main10, VP9
   Profile 2, AV1 Main10), the display's HDR modes, PCM-decodable audio codecs (platform decoders plus
   the FFmpeg extension), and which bitstream formats the HDMI sink accepts for passthrough.
2. **Profile** (`DeviceProfileBuilder`): turns those facts into a Jellyfin `DeviceProfile`: Direct Play
   containers/codecs, per-codec limits (`Width`, `Height`, `VideoBitDepth`, `VideoProfile`,
   `VideoRangeType`), an HLS transcoding profile (fMP4 + HEVC when the device decodes HEVC in hardware,
   otherwise TS + H.264), and subtitle delivery (Embed/External for what libass/Media3 render, Encode
   for anything else, so unsupported subtitles get burned in rather than dropped).
3. **Negotiate** (`PlaybackNegotiator`): `POST /Items/{id}/PlaybackInfo` with the profile, start
   position and chosen streams. The server answers Direct Play (static file URL), Direct Stream
   (remux) or Transcode (HLS URL plus reasons). The token goes in request headers, never in URLs Aurora builds.
4. **Play** (`PlayerController` → `PlaybackEngine`): loads the URL, selects the audio/subtitle track by
   container ordinal (embedded) or registers sidecar files (external), seeks to the resume point, and
   reports start, progress (every 10 s and on pause) and stop to Jellyfin.
5. **Switching tracks**: switching between embedded tracks during Direct Play happens in the engine
   with no reload. Choosing a subtitle that needs burning in, or changing audio while transcoding,
   asks the server for a new stream at the current position.
6. **Errors**: an engine error offers "Try with transcoding", which renegotiates with Direct Play
   disabled. That's the right fallback for decoder or container failures.

## UI and focus

* Every focusable element goes through `FocusSurface`, which gives the same focus lift, ring and
  shadow for D-pad, keyboard and mouse hover. Scale changes go through `graphicsLayer`, so focus
  never triggers relayout.
* `Modifier.arrowKeyNavigation()` moves focus with arrow keys on desktop (Android does this natively
  for the D-pad); Windows controllers are bridged to the same keys through XInput.
* Each back-stack entry keeps its screen model (data, `LazyListState`) and a `FocusMemory`, so Back
  returns to the exact card that was focused.
* Rails use `focusRestorer` so moving up/down into a rail returns to its last-focused card.
* Home rails load lazily: a rail fetches its items when it first scrolls into view.
* Artwork is requested at 2× the card's display size via Jellyfin's `fillWidth`; BlurHash gives an
  instant placeholder and the artwork's accent colour without downloading the image first.

## Windows video composition

mpv renders into a native child window (HWND), which Compose can't paint over. The player opens a
borderless video window exactly over the main window's content area, and the shared player controls
are drawn in a transparent, focusable dialog stacked above it. Both follow the main window when it
moves or resizes. A 1/255-alpha fill keeps the transparent layer hit-testable, so mouse movement can
reveal the controls.

## Security

* Access tokens are stored only in the platform vault (Android Keystore AES-GCM / Windows DPAPI);
  `servers.json` holds no secrets (a test asserts this).
* `Log.redact` strips `api_key`, `Token="…"`, `X-Emby-Token`, `AccessToken` and passwords from every
  log line before it reaches logcat or `aurora.log`.
* Android allows cleartext HTTP (most home servers are LAN HTTP) and user-installed CAs (for
  self-signed certificates). `allowBackup` is off.
