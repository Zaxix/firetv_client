---
tags: [feature, performance]
---
# Playback Resume

## What it does
Starts an episode or movie where it was left, as fast as possible and without extra server work.

## How it works
`ResumeTuning.CURRENT = 3` switches on four measured optimisations (CI can turn them off with
`--ei resumeLevel 0|1|2` to compare on the same build):

| Level | What it adds |
|---|---|
| 1 | Starts at the **keyframe before** the saved position (`SeekParameters.PREVIOUS_SYNC`) instead of decoding up to the exact frame; video goes over the app's already-open connections (`SharedOkHttp`). |
| 2 | Fetches the item and negotiates `PlaybackInfo` **at the same time** instead of one after the other. |
| 3 | For Direct Play MKVs with attachments (anime releases keep 10–40 MB of fonts before the first video cluster), keeps **that header** on the device and fetches it ahead (`MediaHeader`, Android `MediaHeaderCache`). |

The **prewarmer** (`PlaybackPrewarmer`) prepares the title a details page or a focused Continue
Watching card would start: item, track choice and the PlaybackInfo answer. A result is used once,
only if under 90 s old and made with the same session, settings and capabilities. The header
prefetch only runs for titles with a resume position (`startMs > 0`).

## Relevant files
`playback/ResumeTuning.kt`, `playback/PlaybackPreparer.kt` (PlaybackPreparer, PlaybackPrewarmer),
`playback/MediaHeader.kt`, `androidMain/.../MediaHeaderCache.kt`, `androidMain/.../ExoPlayerEngine.kt`,
`androidMain/jellyfin/HttpEngine.kt` (SharedOkHttp).

## Dependencies
[[Video Player]] · [[Caching Strategy]] · [[Server Overview]]

## Data storage
Android cache directory: MKV headers only (≤ 48 MB each, 256 MB in all, least recently used
removed first). Video is never stored. The key includes file id, size and ETag, so a replaced file
never reuses old bytes. Deleted when the episode is finished, or 5 days after it was last left
unfinished; see [[Temporary File Lifecycle]].

## Performance considerations
- Nothing is transcoded ahead of time: the prewarmer asks the server only for the PlaybackInfo
  answer (a decision, not a stream). A transcode starts only when Play is pressed.
- Measured in CI (emulator, real Jellyfin): direct-play resume 4.4–5.2 s → 2.9–3.0 s from a cold
  start, 3.6–3.7 s → 2.0 s from the details page; an anime MKV with large font attachments
  2.96 s / 36.9 MB → 1.39 s / 1.95 MB.

## User interactions
"Resume" / "Play from start" on the details page; Settings → Playback → *Resume behaviour*.

## Known limitations
Timings are from the Android emulator against a CI server; real Fire TV numbers will differ.

## Future improvements
None planned; the spec asks to keep this behaviour as it is.

## Related notes
[[Streaming Performance]] · [[Playback Issues]] · [[Change Log]]
