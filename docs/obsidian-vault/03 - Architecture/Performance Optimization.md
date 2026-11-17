---
tags: [architecture, performance]
---
# Performance Optimization

Priorities, in order: **server load** (the server is modest), then what the viewer waits for, then
device CPU/GPU/memory.

## Home
- Rows show as soon as views + Continue Watching + Next Up answer; banner and recommendations join
  later without blocking ([[Home Page]]).
- The banner reuses the "Recently Added" responses (no extra requests).
- Saved copy from disk races the network.
- Request counting (Android `SharedOkHttp` interceptor) logs API / image / media requests in the
  first 10 s so changes can be measured.

## Playback
See [[Playback Resume]] and [[Streaming Performance]].

## Rendering
- Single full-screen frosted draw from a BlurHash ([[Frosted Glass Effects]]).
- Player overlay doesn't redraw while video plays with controls hidden.
- Small lazy cache windows (rows: half a screen ahead, a quarter behind).

## Measuring
CI (`ci/android_release_smoke.sh`, `ci/android_e2e.sh`) prints Home fill time, request counts and
resume timings; results are listed in [[Change Log]].

## Related notes
[[Optimization Notes]] · [[Caching Strategy]] · [[Resource Usage]]
