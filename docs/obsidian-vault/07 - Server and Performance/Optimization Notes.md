---
tags: [performance]
---
# Optimization Notes

Decisions and the reasoning behind them, so future changes don't undo them by accident.

- **Banner shares row loads.** The banner is picked from the rows' own "Recently Added" responses,
  so it costs no requests of its own; keep it that way.
- **Rows don't wait for the banner or history.** Waiting made every row as slow as the slowest
  library query.
- **Recommendations without the catalogue.** Server-side genre + unplayed filters return a few
  dozen items instead of the whole library; rows are reused while the history is unchanged.
- **Prewarm the decision, not the stream.** PlaybackInfo is cheap; starting a transcode early isn't.
- **Header prefetch only with a resume point.** Starting from the beginning reads the header anyway.
- **Device profiles cap id lists at 100** to keep URLs well under server limits.
- **Not done:** preloading posters for off-screen rows, background refresh timers, persistent
  library caches — they'd add server load for little gain.

## Related notes
[[Performance Optimization]] · [[Hardware Limitations]]
