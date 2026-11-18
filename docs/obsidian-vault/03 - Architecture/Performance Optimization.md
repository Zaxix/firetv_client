---
tags: [architecture, performance]
---
# Performance Optimization

Priorities, in order: **server load** (the server is modest), then what the viewer waits for, then
device CPU/GPU/memory.

## Home
- Rows show as soon as views + Continue Watching + Next Up answer; banner and recommendations join
  later without blocking ([[Home Page]]).
- The banner is picked from the rows' "Recently Added" responses (no extra requests).
- Saved copy from disk races the network.
- Home opened again in the same session comes from memory: no requests for 5 minutes, and the
  recommendation history only when something was watched (see [[Recommendations]]).
- Request counting (Android `SharedOkHttp` interceptor) logs API / image / media requests in the
  first 10 s so changes can be measured.

## Playback
See [[Playback Resume]] and [[Streaming Performance]].

## Skip markers
Remembered markers show before any request; audio windows are fetched two at a time (never more),
one detection at a time, season lists and windows kept in memory, and each audio encode is ended on
the server as soon as its window is read. Listening detection on CI: 2.7 s → 1.2–1.3 s
([[Skip Markers]]).

## Detail pages
Upcoming air dates load after the page is shown (one small request, 6 h per series in memory, none
for ended series) ([[Details Page Layout]]).

## Rendering
- Single full-screen frosted draw from a BlurHash ([[Frosted Glass Effects]]).
- Player overlay doesn't redraw while video plays with controls hidden.
- Small lazy cache windows (rows: half a screen ahead, a quarter behind).

## Measuring
CI (`ci/android_release_smoke.sh`, `ci/android_e2e.sh`) prints Home fill time, request counts,
Home-opened-again time and requests (`Perf: Home opened again`), skip-marker timing
(`Perf: Skip markers … in X ms`, `SkipDetector: Timings`) and resume timings; results are listed in [[Change Log]].

## Related notes
[[Optimization Notes]] · [[Caching Strategy]] · [[Resource Usage]]
