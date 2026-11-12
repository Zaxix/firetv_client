# Screenshots

Captured automatically by CI from the real builds, using a real Jellyfin server and a synthetic test
library (all artwork, titles and video are generated, nothing third-party).

## Fire TV / Android TV (Android 11 TV emulator, driven only by remote key presses)

| | |
|---|---|
| ![](android-tv-00-connect.jpg) Connect | ![](android-tv-01-home.jpg) Home: featured carousel + rails |
| ![](android-tv-02-dpad-rail-focus.jpg) D-pad Down into a rail (focus lift) | ![](android-tv-03-movie-detail.jpg) Select → detail page |
| ![](android-tv-04-back-focus-restored.jpg) Back → focus restored to the same card | ![](android-tv-05-side-menu.jpg) D-pad Left → side menu expands |
| ![](android-tv-06-series-detail.jpg) Series detail | ![](android-tv-08-player-controls.jpg) Player controls |
| ![](android-tv-07-libass-anime-typesetting.jpg) libass: rotated/moving signs, `\clip`+`\t`, karaoke, overlapping lines | ![](android-tv-09-hevc-main10-styled-ass.jpg) Styled ASS (colours, `\pos`, simultaneous lines) |
| ![](android-tv-10-about-capabilities.jpg) Settings → About: detected capabilities | |

## Desktop (same shared UI; Linux build under Xvfb in CI)

| | |
|---|---|
| ![](desktop-01-home-dark.jpg) Home (Dark) | ![](desktop-05-home-light.jpg) Home (Light) |
| ![](desktop-02-movie-detail.jpg) Movie detail | ![](desktop-03-series-episodes.jpg) Series with episodes |
| ![](desktop-06-series-light.jpg) Series (Light) | ![](desktop-04-movies-library.jpg) Library with sort/filter |
| ![](desktop-07-settings.jpg) Settings | ![](windows-01-first-launch.jpg) Packaged Windows app, first launch on a Windows runner |

Windows playback (mpv video window with the transparent Aurora controls on top) is captured each CI run
against the public Jellyfin demo server; those images contain third-party film artwork, so they stay
in the CI results only (`ci-results` branch, `screenshots/windows/`).
