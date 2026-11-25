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
- **Skip detection compares with the *next* episode first.** The neighbour's markers come out of
  the same comparison, so when it's played next nothing is fetched or analysed for it (for the kinds
  that comparison found): up to about half the audio windows the server encodes while binge-watching.
- **A detection that found nothing is remembered too.** Before, only found markers were kept, so an
  episode with no detectable intro/outro was re-analysed (4 audio windows) on every resume.
- **One season-list request per detection**, shared by the chapter, audio and screen steps (the
  chapter and audio steps each fetched it before).
- **The outro screen check reads thumbnails the server already made** (static JPEGs), never video,
  and only when the outro is still missing.
- **The series is looked up for the audio default only when it changes the track** (an English track
  exists, more than one track, and the default isn't English), once per series per session.
- **Temporary files are cleaned on events**, plus one sweep per app start: no timers, no scanning.
- **Not done:** preloading posters for off-screen rows, background refresh timers, persistent
  library caches, analysing video frames on the device, re-checking finished detections — they'd
  add server or device load for little gain.

## Related notes
[[Performance Optimization]] · [[Hardware Limitations]]
