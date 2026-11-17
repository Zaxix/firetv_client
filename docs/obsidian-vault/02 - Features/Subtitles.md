---
tags: [feature]
---
# Subtitles

## What it does
Full ASS/SSA rendering (libass on both platforms), styled text subtitles, remembered track choices
per series.

## How it works
- ASS/SSA: libass overlay on Android (or *Simple* mode for weak devices), built into mpv on Windows.
  When the server transcodes video, ASS stays ASS (sent separately) rather than being burned in.
  Unsupported formats are burned in by the server (`Encode`).
- Text subtitles use `SubtitleStyle` (size, colour, background, edge); ASS keeps its own styling.
- `TrackMemory` remembers hand-picked tracks per series/movie by *what they are* (language, kind —
  forced, signs & songs, full — title, codec, channels), so the same choice applies to the next
  episode even if stream indexes differ. Newest 500 kept.
- For transcodes with ASS, the MKV's font attachments are downloaded alongside the PlaybackInfo
  request (≤ 64 MB, 6 at a time) and dropped if the server picks Direct Play.

## Relevant files
`playback/TrackSelector.kt`, `playback/TrackMemory.kt`, `playback/SubtitleStyle.kt`,
`playback/PlaybackPreparer.kt`, `ui/components/SubtitlePreview.kt`.

## Dependencies
[[Video Player]] · [[Playback Resume]]

## Data storage
`track-memory-<profile>.json` (TrackMemory). Settings hold the style.

## Performance considerations
libass rendering is capped at 1080p on 4K TVs.

## User interactions
Player → Down → Subtitles panel; Settings → Subtitles.

## Known limitations
No subtitle delay on Android; embedded fonts are unavailable while transcoding.

## Related notes
[[Playback Issues]] · [[Known Issues]]
