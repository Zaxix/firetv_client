# Screenshots

Captured automatically by CI from the real builds, using a real Jellyfin server and a synthetic test
library (all artwork, titles and video are generated, nothing third-party).

## Fire TV / Android TV (Android 11 TV emulator, driven only by remote key presses)

| | |
|---|---|
| ![](android-tv-00-connect.jpg) Connect | ![](android-tv-01-home.jpg) Home: sidebar rail, full-screen featured title |
| ![](android-tv-02-dpad-rail-focus.jpg) D-pad Down into a rail (tvOS lift + sheen) | ![](android-tv-03-movie-detail.jpg) Select → detail page |
| ![](android-tv-04-sidebar.jpg) Back → the sidebar opens | ![](android-tv-05-series-detail.jpg) Series detail with episodes |
| ![](android-tv-07-player-yellow-intro-credits.jpg) Player: intro and credits ranges in yellow on the scrubber, Back, Episodes/Chapters/Subtitles/Info | ![](android-tv-06-libass-anime-typesetting.jpg) libass: rotated/moving signs, `\clip`+`\t`, karaoke |
| ![](android-tv-10-player-episode-browser.jpg) In-player episode browser (Down from the timeline) | ![](android-tv-08-hevc-main10-styled-ass.jpg) Styled ASS (colours, `\pos`, simultaneous lines) |
| ![](android-tv-09-about-capabilities.jpg) Settings → About: detected capabilities | ![](android-tv-11-settings-ratings.jpg) Settings → Ratings: optional MDBList / Trakt keys |

## Desktop (same shared UI; Linux build under Xvfb in CI)

| | |
|---|---|
| ![](desktop-01-home-dark.jpg) Home (Dark) | ![](desktop-05-home-light.jpg) Home (Light) |
| ![](desktop-02-movie-detail.jpg) Movie detail with the critics score from the server | ![](desktop-03-series.jpg) Series |
| ![](desktop-06-series-light.jpg) Series (Light) | ![](desktop-04-movies-library.jpg) Library with sort/filter |
| ![](desktop-07-settings.jpg) Settings | ![](windows-01-first-launch.jpg) Packaged Windows app, first launch on a Windows runner |

## Ratings and reviews (rendered by the UI test with sample data)

The CI server has no MDBList/Trakt keys, so these two come from `RatingsUiTest`, which renders the real
components with sample scores and reviews (marked as such here; no live data).

| | |
|---|---|
| ![](ui-ratings-and-reviews.jpg) Critics (tomato) and audience (popcorn) meters, IMDb, Metascore, Letterboxd; spoiler-free review cards with each reviewer's score | ![](ui-review-overlay.jpg) A review opened in full (Up/Down scroll, Back closes) |

Windows playback (mpv video window with the transparent Sharkfin controls on top) is captured on every
CI run against the public Jellyfin demo server. Those images contain third-party film artwork, so they
stay in the CI results only (`ci-results` branch, `screenshots/windows/`).
