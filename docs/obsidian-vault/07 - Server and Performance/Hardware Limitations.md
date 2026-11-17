---
tags: [performance]
---
# Hardware Limitations

- **Transcoding is the expensive part.** The device profile prefers Direct Play; for transcodes,
  H.264 is listed first because HEVC encodes are several times slower on typical home servers, and
  an HEVC source that only needs new audio is copied rather than re-encoded when the device decodes
  HEVC in hardware.
- **Unsupported subtitles** get burned in (costly); ASS stays ASS whenever the device can render it.
- **Audio windows for skip detection** are small low-rate audio transcodes (~4 KB/s), only for the
  episode being watched.
- Request bursts: Home's requests run in parallel but are a fixed, small set ([[Resource Usage]]).

## Related notes
[[Streaming Performance]] · [[Optimization Notes]]
