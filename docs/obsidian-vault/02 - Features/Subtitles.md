---
tags: [feature]
---
# Subtitles

## What it does
Full ASS/SSA rendering (libass on both platforms), styled text subtitles, remembered track choices
per series.

## How it works
- ASS/SSA: libass overlay with ExoPlayer (or *Simple* mode for weak devices), built into mpv with the mpv player.
  When the server transcodes video, ASS stays ASS (sent separately) rather than being burned in.
  Unsupported formats are burned in by the server (`Encode`).
- Text subtitles use `SubtitleStyle` (size, colour, background, edge); ASS keeps its own styling.
- `TrackMemory` remembers hand-picked tracks per series/movie by *what they are* (language, kind —
  forced, signs & songs, full — title, codec, channels), so the same choice applies to the next
  episode even if stream indexes differ. Newest 500 kept.
- For transcodes with ASS, the MKV's font attachments are downloaded alongside the PlaybackInfo
  request (≤ 64 MB, 6 at a time) and dropped if the server picks Direct Play.

## Which track is chosen automatically (`TrackSelector.pickSubtitle`)
Order of precedence, first match wins:
1. **A track picked by hand** earlier in the series (TrackMemory, matched by kind, so a hand-picked
   Signs & Songs track stays Signs & Songs on the next episode even when indexes differ).
2. **Dialogue, when the audio isn't in the viewer's language** (blank preference = English):
   full track → SDH/CC (`IsHearingImpaired` flag or "SDH/CC/HI" in the title) → other tracks
   (commentary) → Signs & Songs / forced only if nothing else exists. Ties: the file's default flag,
   then the server's default, then file order. Never list order alone.
3. **Signs & Songs on purpose** only for a dub (audio already in the viewer's language) or in
   "Only forced" mode.

How a track's kind is read (`subtitleKind`): the S&S title pattern ("Signs & Songs", "S&S", "Songs/Signs",
"Signs"), a forced flag or "Forced"/"Foreign parts only" in the title → signs; but a title saying
"Full"/"Dialogue"/"Complete" always wins (a full track mislabelled forced is still full). Untagged or
"und" language falls back to untagged tracks; with no track in the viewer's language, the language the
file flags as default is used.

**Default mode** ("as the file says") shows subtitles when a track is flagged default or forced or the
server names one, and also whenever the audio is in another language and there's a track in the
viewer's language. Which track is shown follows the order above, not the flag.

### The Signs & Songs bug (fixed Nov 2026)
Root causes:
- In Default mode with no default-flagged track, the old code went straight to "a forced track",
  and release groups often flag S&S as forced, so S&S beat the full English track.
- A blank subtitle preference meant "no language", so nothing preferred English dialogue.
- The players could pick a track themselves before ours was applied: Media3 turns on a
  default/forced-flagged text track, and if our pick wasn't among the first tracks reported it was
  never retried. mpv also starts with the default/forced track.

Fixes:
- The ranking above.
- Media3 now ignores default/forced flags (`setIgnoredTextSelectionFlags`) and retries the pick when
  more tracks appear.
- mpv starts with `sid=no` until our pick is applied.

Tests: `SubtitleSelectionTest`, covering full + S&S (flagged forced, default, server default, unflagged),
SDH + S&S, only S&S, ambiguous names, dubs, missing metadata, a hand-picked S&S choice on the next
episode, and subtitles turned off by hand.

## Film fonts (Nov 2026)
Normal text subtitles (SRT, WebVTT and the like) can use a font that suits the film. For now only
the Harry Potter films have one (`playback/SubtitleThemes.kt`).

- **Which films:** a film whose TMDB collection is 1241 ("Harry Potter Collection") or whose title
  starts "Harry Potter and the …". Documentaries, series and every other film are unchanged.
- **The font:** *IM Fell English* by Igino Marini, under the SIL Open Font License (`LICENSES/IMFellEnglish-OFL.txt`).
  - It's an old-style printed face with a storybook feel that stays readable at subtitle size.
  - It's shown 10% larger, because its letters run small for their size.
  - The regular and the real italic (for `<i>` lines) are bundled in the app
    (`composeResources/files/subtitle-fonts/`, ~400 KB), so nothing is downloaded and the server isn't
    involved. They're read once per run.
  - It isn't the films' own lettering: that's a trademark, and the "Harry Potter" fonts online are
    fan copies that aren't licensed to ship in an app.
- **Engines:**
  - **Media3:** a `Typeface` from the files, made once (regular + italic as one family on Android 10+),
    set in `CaptionStyleCompat`.
  - **mpv:** the files go into its fonts folder with the stream, and `sub-font` names the family.
  - Both keep your colour, edge, box, bold, position and size settings.
- **Never changed:** styled ASS/SSA subtitles keep their own fonts, and picture subtitles (PGS,
  VobSub) can't change.
- **Settings → Subtitles → Film fonts:** On by default; Off uses the usual font everywhere.
- **Adding films:** add a `SubtitleTheme` (fonts under `files/subtitle-fonts/`, their licence under
  `LICENSES/`, and a `matches` rule) to `SubtitleThemes.all`.
- **Tests:** `SubtitleThemesTest` checks which films match, that the font is read once with its
  italic, and that mpv gets the family for plain subtitles only.
- **Not seen yet:** the font hasn't been checked on a real Fire TV screen. The settings sample
  (`SubtitlePreview`) still shows the usual font.

## Relevant files
`playback/TrackSelector.kt`, `playback/TrackMemory.kt`, `playback/SubtitleStyle.kt`,
`playback/PlaybackPreparer.kt`, `ui/components/SubtitlePreview.kt`; Android players:
`android/ExoPlayerEngine.kt` (`selectSubtitle`), `android/MpvAndroidEngine.kt` (`load`).

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
