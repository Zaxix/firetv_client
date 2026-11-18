---
tags: [bug]
---
# Playback Issues

- **Slow resume** (fixed): see [[Playback Resume]] for the four optimisations and measurements.
- **Anime `.ass` resume stalls** (fixed): font-heavy MKV headers re-downloaded on each start; now
  cached and prefetched (Android).
- **Transcode not stopped after leaving** (prevented): `DELETE /Videos/ActiveEncodings` on stop.
- **Device-only profiles and transcodes:** progress isn't reported to the account, so the player
  sends `/Sessions/Playing/Ping` with the play session to keep a transcode alive.
- Open: subtitle delay on Android; DV/HDR on real hardware ([[Known Issues]]).

## Related notes
[[Video Player]] · [[Streaming Performance]]
