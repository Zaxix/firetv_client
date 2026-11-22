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
- **Air dates:** upcoming air dates load after the page is shown: one small request, kept 6 hours
  per series in memory, none for ended series ([[Details Page Layout]]).
- **"More Like This"** (the server's heaviest query on the page: it compares the title with the
  whole library):
  - asked for once per page, after the page's own data, so it never delays it or competes with it;
  - not asked again on each return from the player;
  - its answers are kept 10 minutes (24 titles at most) and dropped when anything is watched.
- **Seasons:** a season already shown on the page comes back instantly, with no request. A slower
  answer for a season you've switched away from never replaces the one now shown (this fixed a real
  race).
- **Playing from a film or episode page:** getting playback ready uses the item the page already
  fetched (one PlaybackInfo request instead of the item plus one or two PlaybackInfo requests). An
  item from before a reload is never used, so the resume point is always current.

## Home banner
The banner's Play target (a show's next episode) is worked out once per show and reused until
something is watched. Before, it was worked out again every time Home came back, costing a Next Up
request, an episode list and a new playback preparation each time.

## Rendering
- Single full-screen frosted draw from a BlurHash ([[Frosted Glass Effects]]).
- Player overlay doesn't redraw while video plays with controls hidden.
- Small lazy cache windows (rows: half a screen ahead, a quarter behind).

## Performance audit (Nov 2026)
**How it was measured:** `ci/android_perf.sh` runs on the release APK in the Android TV emulator. One
build carries both versions of the changed code: `--ei perfLevel 0` is before the audit, `1` is
after. The two run in alternating rounds (0 1 0 1 0 1) on the same emulator, because the CI runner's
speed changes too much between runs to compare runs with each other.

Each round:
1. Cold start to Home, with a probe that samples the UI thread whenever it's blocked
   (`--ez perfTrace true`) and a log of every API request (path and query, keys removed).
2. Eight film and show pages opened from Home and closed again.
3. A two-season show: switch season, switch back, switch again.
4. A film played from its page.
5. Memory (PSS).

**Findings:**
- **Start-up:** the UI thread is mostly blocked drawing frames: about 1.5–1.7 s in the first 12 s,
  almost all in `HardwareRenderer.syncAndDrawFrame`. The emulator draws with a software GPU, so
  this says little about a Fire TV. Sharkfin's own code (composition, fonts, decoding) was tens of
  ms. Start-up wasn't changed: there was no evidence of an app-side cost to remove.
- **Repeated requests:** about 33 of 84 requests in a round were exact repeats. The causes:
  - "More Like This" asked for again on every page open and return;
  - the page's item asked for twice (by the page, then by playback preparation);
  - PlaybackInfo asked for twice when the early answer didn't fit the chosen tracks;
  - the banner's Next Up, episode list and playback preparation each time Home came back.

**Results** (medians of the rounds; one run, alternating rounds; emulator, not a Fire TV):

| Metric | Before (level 0) | After (level 1) | Change |
|---|---|---|---|
| Film page fully loaded | 77 ms (23 opens) | 55 ms (21 opens) | −29% |
| API requests per film page | 2 | 1 | −50% |
| API requests per show page | 5 | 4 | −20% |
| Show page fully loaded | 210 ms (3) | 226 ms (5) | no clear change (few samples, different shows) |
| Season switch shown | 14 ms, 1 request | 6 ms; 0 ms and 0 requests for a season seen before | faster |
| Playing a film from its page | 990 ms (2) | 952 ms (3) | −4% (within noise) |
| API requests in a round | 84 | 75 | −11% |
| Exact repeats in a round | 33 | 25 | −24% |
| Home shown (saved copy), cold start | 974 ms | 1050 ms | no change (noise; not touched) |
| UI thread blocked, first 12 s | 1608 ms | 1560 ms | no change (rendering) |
| Memory (PSS) after a round | 112.7 MB | 112.5 MB | no change |

**Not measured:**
- Fire TV hardware;
- remote input-to-focus latency (the emulator's frame times are dominated by software rendering);
- profile switching;
- search;
- hours of use;
- Jellyfin server CPU and RAM.

The fewer and lighter requests should lower server CPU, "More Like This" most of all, but server
load wasn't measured.

**Recommended next:**
- Run the same `perfTrace` probe on a real Fire TV (`adb shell am start … --ez perfTrace true`) to
  see whether start-up there is also spent drawing. If it is, look at what draws during start-up
  (skeleton shimmer, the full-screen backdrop).
- Add a remote-latency measurement on hardware (key event to next drawn frame).
- Measure server CPU during a browsing round with a large library, where "More Like This" costs
  the most.

## Measuring
The performance audit's A/B rounds (`ci/android_perf.sh`, output in `perf-audit.txt`) are described
above. `PerfTuning.level` selects the code before or after the audit; normal use is always the
current code. CI (`ci/android_release_smoke.sh`, `ci/android_e2e.sh`) prints Home fill time, request counts,
Home-opened-again time and requests (`Perf: Home opened again`), skip-marker timing
(`Perf: Skip markers … in X ms`, `SkipDetector: Timings`) and resume timings; results are listed in [[Change Log]].

## Related notes
[[Optimization Notes]] · [[Caching Strategy]] · [[Resource Usage]]
