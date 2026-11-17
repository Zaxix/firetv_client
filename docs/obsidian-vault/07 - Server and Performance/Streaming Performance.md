---
tags: [performance]
---
# Streaming Performance

- Direct Play whenever the device can decode the file; remux next; transcode last.
- Keyframe starts and seeks; shared warm connections for video (Android).
- Buffers: Media3 2 min / 160 MB ahead, 15 s behind; mpv 400 MiB ahead, 100 MiB back.
- MKV header cache: resume downloads only the video around the resume point, not 10–40 MB of fonts.
- The prewarmer asks only for the playback *decision*; no stream starts until Play.
- Transcodes are stopped on the server when playback ends.

Measured (CI emulator): direct-play resume 2.9–3.0 s cold, 2.0 s from details; anime MKV resume
1.39 s, 1.95 MB (was 2.96 s, 36.9 MB).

## Related notes
[[Playback Resume]] · [[Video Player]]
