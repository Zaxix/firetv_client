---
tags: [architecture]
---
# System Architecture

Sharkfin is a Fire TV / Android TV app built as one Kotlin Multiplatform module plus a thin Android
shell. The Jellyfin client, state, UI and player controls are in the shared code; only
decoding/rendering, secure storage, capability probing and a few platform services are
Android-specific. The shared code also compiles for the JVM, which is used only to run tests (see
[[Supported Platforms]]).

```mermaid
flowchart TB
    subgraph Shared["shared (commonMain)"]
        UI[ui: App shell, screens, focus, player chrome, sounds]
        NAV[ui/nav: Navigator, FocusRegions]
        PB[playback: negotiation, PlayerController, SkipDetector + CreditsVision, prewarm, EpisodeTempFiles]
        DATA[data: SessionManager, WatchActivity]
        API[jellyfin: JellyfinApi, ImageUrls, LocalWatchData overlay]
        SVC[recommend · search · ratings · brands · update · settings · profiles]
    end
    subgraph Android["androidMain"]
        EXO[ExoPlayerEngine / MpvAndroidEngine]
        KS[KeystoreVault]
        OK[SharedOkHttp · LanRoute · MediaHeaderCache]
        SP[AndroidUiSounds]
    end
    subgraph Tests["jvmMain (test host only)"]
        JT[OkHttp engine · image decoding]
    end
    UI --> NAV --> PB --> API
    UI --> SVC --> API
    DATA --> API
    API -->|HTTPS/HTTP| JF[(Jellyfin server)]
    PB --> EXO
    Shared -. Platform interface .- Android
    Shared -. unit, UI and server tests .- Tests
```

`AppContainer` (`ui/AppContainer.kt`) wires the services together; `AuroraApp`/`MainShell` in
`ui/App.kt` is the shell. The `Platform` interface (`platform/Platform.kt`) is everything a platform
must provide: file store, secure vault, capability probe, playback engine, player host, and optional
services (updater, UI sound output, request statistics, VPN detection, media-header prefetch and
its disposable cache).

## Related notes
[[Frontend Architecture]] · [[Backend Architecture]] · [[Data Storage]] · [[Project Structure]]
