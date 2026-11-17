---
tags: [architecture]
---
# System Architecture

Sharkfin is one Kotlin Multiplatform codebase with two thin native shells. The Jellyfin client,
state, UI and player controls are shared; only decoding/rendering, secure storage, capability
probing and a few platform services are native.

```mermaid
flowchart TB
    subgraph Shared["shared (commonMain)"]
        UI[ui: App shell, screens, focus, player chrome, sounds]
        NAV[ui/nav: Navigator, FocusRegions]
        PB[playback: negotiation, PlayerController, SkipDetector, prewarm]
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
    subgraph Desktop["desktopMain"]
        MPV[MpvEngine via JNA]
        DP[DPAPI vault · DesktopStorage]
        DS[DesktopUiSounds]
    end
    UI --> NAV --> PB --> API
    UI --> SVC --> API
    DATA --> API
    API -->|HTTPS/HTTP| JF[(Jellyfin server)]
    PB --> EXO & MPV
    Shared -. Platform interface .- Android & Desktop
```

`AppContainer` (`ui/AppContainer.kt`) wires the services together; `AuroraApp`/`MainShell` in
`ui/App.kt` is the shell. The `Platform` interface (`platform/Platform.kt`) is everything a platform
must provide: file store, secure vault, capability probe, playback engine, player host, and optional
services (updater, UI sound output, request statistics, VPN detection, media-header prefetch).

## Related notes
[[Frontend Architecture]] · [[Backend Architecture]] · [[Data Storage]] · [[Project Structure]]
