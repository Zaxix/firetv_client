---
tags: [overview]
---
# Project Structure

```text
firetv_client/
├── shared/                         Kotlin Multiplatform module (almost all code)
│   └── src/
│       ├── commonMain/kotlin/com/auroratv/
│       │   ├── core/               AppError (user-facing messages), Log (token-redacting), JSON
│       │   ├── data/               SessionManager (servers, users, tokens, profiles), WatchActivity
│       │   ├── jellyfin/           JellyfinApi (Ktor), ServerAddress, ImageUrls, model/ DTOs
│       │   ├── playback/           negotiation, PlayerController, engines interface, SkipDetector,
│       │   │                       AudioFingerprint, MediaHeader, ResumeTuning, TrackMemory, subtitles
│       │   ├── profiles/           per-profile stores (ratings, skip markers, search history)
│       │   ├── recommend/          RecommendationEngine + RecommendationService
│       │   ├── ratings/            MDBList/Trakt ratings & reviews, key vault
│       │   ├── search/             SearchIndex (fuzzy) + SearchService
│       │   ├── brands/             streaming-brand hubs from studio metadata
│       │   ├── settings/           Settings model + per-profile persistence
│       │   ├── update/             in-app updates (latest-build release)
│       │   ├── platform/           Platform interface (what each OS must provide)
│       │   └── ui/                 App shell, nav/, screens/, components/, theme/, sound/
│       ├── commonMain/composeResources/   drawables, fonts, files/ (UI sounds)
│       ├── androidMain/            ExoPlayer & mpv engines, capabilities, Keystore vault,
│       │                           SharedOkHttp, LanRoute (VPN), MediaHeaderCache, sounds
│       ├── desktopMain/            mpv engine (JNA), desktop platform/storage/updater, sounds
│       ├── commonTest/             unit tests (fake server via Ktor MockEngine)
│       └── desktopTest/            integration tests against a real Jellyfin, UI tests
├── androidApp/                     MainActivity (thin shell; CI launch extras)
├── desktopApp/                     Main.kt, XInput GamepadBridge, packaging
├── ci/                             emulator/desktop/Windows scripts, sample-media generator
├── docs/                           ARCHITECTURE, CAPABILITIES, KNOWN_LIMITATIONS, TEST_RESULTS,
│   └── obsidian-vault/             this vault
└── .github/workflows/build.yml     the whole pipeline
```

Key entry points: `AppContainer` (wires services), `AuroraApp`/`MainShell` in `ui/App.kt`
(the shell), `PlayerController` (playback logic).

## Related notes
[[Tech Stack]] · [[Frontend Architecture]] · [[System Architecture]]
