---
tags: [feature]
---
# Skip Markers (Skip Intro / Skip Outro)

## What it does
Marks intro, recap and outro (end credits) sections on the timeline in yellow and shows **Skip
Intro**, **Skip Recap** and **Skip Outro** buttons at the right moment. When the credits run to the
end of the episode and there's a next one, the button says **Next Episode** instead; when something
follows the credits (a post-credits scene, a "next time" preview), **Skip Outro** jumps to it and never
past it.

## Where markers come from (best first)
Later sources only fill kinds the earlier ones lack.
1. **Server media segments** (`/MediaSegments/{id}`, e.g. the Intro Skipper plugin).
2. **Chapter names** ("Opening", "Ending", "Credits", "Outro"…).
3. **Detection on the device** (`SkipDetector`), for whatever is still missing:

| Step | Finds | How | Server cost |
|---|---|---|---|
| Recurring chapters | intro, outro | an unnamed chapter of the same length (±1.5 s) in other episodes of the season, near the start / end | none (chapter list) |
| AniSkip (anime only, optional) | intro, outro, recap | crowd-sourced times by MyAnimeList id | none (online service) |
| Matching audio | intro, outro | low-rate audio (8-bit mono, 4 kHz) of this episode and a neighbour → band-shape fingerprints (`AudioFingerprint`) → the longest shared stretch (≥ 12 s) | small audio-only windows (~4 KB/s): intro window ≤ 10 min, outro window ≤ 4 min |
| **Credits on screen** (new) | outro | the server's **trickplay thumbnails** of the last quarter (≤ 10 min): "text on black" frames (`CreditsVision`), confirmed by a stretch of similar length in a neighbouring episode | static thumbnail sheets the server already made (1–2 JPEGs per episode); nothing decoded or transcoded |

```mermaid
flowchart TD
    A[Episode starts playing] --> B{Server segments / chapter names?}
    B -->|all kinds| Z[Done]
    B -->|missing kinds| C{Remembered for this episode?}
    C -->|found or searched before| Z
    C -->|never searched| D[Recurring chapters]
    D --> E[AniSkip — anime, if allowed]
    E --> F[Matching audio: next episode, then previous for the outro]
    F --> G{Outro still missing and thumbnails exist?}
    G -->|yes| H[Credits on screen, confirmed by a neighbour]
    G -->|no| R
    H --> R[Remember: markers found + kinds searched]
    R --> Z
```

## Why outros failed on live-action shows, and what changed
- **Different music every episode.** Many live-action shows play a different song (or no music) over
  the closing credits, so there's nothing shared to hear. → The **on-screen check** looks for the
  credits' *look* instead: white/grey thin text on black.
- **Only one neighbour was compared.** A finale or a special ending made the single comparison fail.
  → The outro now tries a **second neighbour** (this episode's audio is fetched once).
- **An intro-only result stopped all further searching.** Once markers were remembered for an
  episode (found while comparing with a neighbour), detection never ran again, so a missing outro
  stayed missing. → Markers now remember **which kinds were searched**; only kinds never searched are
  looked for. Entries from before this update count as "not searched", so they get one more try.

## The on-screen check (`playback/CreditsVision.kt`)
Each thumbnail (decoded at half size, ~160×90) is classed from a few pixel statistics:
- **Credits**: ≥ 78% near-black, 0.4–20% bright pixels, ≤ 18% mid-tones, the bright pixels are
  colourless (white/grey text), and they're **thin**: ≥ 0.5 dark↔bright changes per bright pixel
  along each row (text strokes; a lamp or the moon in a night scene is a blob and fails this);
- **Black**: ≥ 98% black (fades);
- **Scene**: anything else.

A stretch counts as credits when it is a run of Credits/Black thumbnails (single-frame interruptions,
e.g. a coloured company logo, are tolerated) with ≥ 3 Credits frames, at least half the run, lasting
≥ 30 s, starting after 70% of the runtime and ending within 4 minutes of the end. **It must be
confirmed**: the next (or previous) episode has a stretch of similar length (≥ 60% of the longer one,
or within 20 s). A dark ending in one episode is never enough.

Times are conservative: the outro starts at the first credits thumbnail (never earlier). If something
follows it, it ends one second after the last credits thumbnail, so the skip lands before the next
scene starts (it may leave a few seconds of credits). If it runs to the end, it ends at the end.

## Faster and more accurate (Nov 2026)
**Why it was slow.**
- Every audio window was fetched one after another: this episode's opening, the neighbour's opening,
  this episode's end, the neighbour's end, then sometimes a second neighbour.
- Remembered markers waited for the server's media-segments request before they were shown.
- AniSkip's two server lookups (series, season) ran one after the other.
- Nothing was kept between episodes, so the next episode fetched the season list again.

**Order now:** memory/stored (shown at once, before even the media-segments request) → server
segments and chapter names → recurring chapters → AniSkip (online, if on) → listening → screen
(the last two only for kinds still missing).

**What changed:**
- **Parallel pairs.** This episode's window and the neighbour's are fetched at the same time, but
  never more than two requests at once. Only one detection runs at a time (`Semaphore(1)`), and a
  second request for the same episode waits for the first (`running`).
- **Kept in memory.** The season list is kept 10 minutes (4 seasons) and the last 4 audio windows are
  kept too, so a neighbour's window is never fetched twice. The AniSkip series and season lookups run
  together.
- **Less server work.** An audio encode is ended (`DELETE /Videos/ActiveEncodings` for that play
  session only) as soon as its window has been read, rather than left to run on. No video is ever
  decoded, and the windows are the same size as before (the opening quarter, at most 10 min; the last
  fifth, at most 4 min; 4 kHz 8-bit mono, ≈4 KB/s).

**Accuracy:**
- **Exact edges, never into the story** (`AudioFingerprint.refine`). The rough match's ±1 s smoothing
  let edges reach into the programme where stray codes agreed. The edge is now the first (last) frame
  that agrees within 2 bits, with a neighbouring frame agreeing too and at least 4 of the 10 frames
  inward. The start then moves in one frame and the end three.
  - Tuned on 20 synthetic episode pairs in a Python replica: no edge reached into the programme,
    at most ~1.4 s of theme left at an edge.
  - Checked on CI's real Jellyfin decode: the first version reached 0.86 s into the story there,
    which led to the "second agreeing frame" rule.
- **Silent gaps.** An edge within ~0.25 s outward / 0.9 s inward of a silent gap (RMS < 0.015 for
  ≥ 100 ms) moves into it, between the music and the story.
- **Post-credits scenes.**
  - Credits run to the end of the file only when nothing but silence follows the music (≥ 90 % of
    1-s blocks quiet) or it ends within 3 s of the end. Previously the music ending anywhere in the
    last 20 s extended the credits to the end.
  - "Next Episode" replaces "Skip Outro" only when confident credits end within 8 s of the end
    (was 20 s).
- **Confidence.** Each range has `confident`. A match under 20 s, with under half its frames agreeing,
  or without exact edges (then pulled in 5 frames), is *weak*: the Skip button is shown, but it is
  never auto-skipped, never turns into "Next Episode", and a later source can replace it. A recurring
  chapter seen in only one other episode (when there are more to compare) is weak too. Weak ranges
  are stored with a `?` in `skip-markers-<profile>.json`.

**Measured** (CI, real Jellyfin (`jellyfin/jellyfin:latest`) in Docker, generated episodes; integration test):

| | Before | After |
|---|---|---|
| Wavelength ep 2 intro (true 8–38 s) | 7.375–38.5 s (0.6 s of cold open and 0.5 s of story skipped) | 8.0–37.625 s |
| Wavelength ep 2 credits start (true 215 s) | 214.144 s (0.86 s of story skipped) | 215.144 s |
| Wavelength ep 3 credits, 15 s scene after (new sample) | — | 215.144–239.394 s, Skip Outro lands on the scene |
| Harbor Precinct outro on screen (true 170–215, scene after) | 170.0–211.0 s | 170.0–211.0 s (unchanged) |
| Listening detection, whole | 2683 / 2702 ms | 1307 ms |

## Accuracy over coverage
- No outro is ever assumed from "the last few minutes".
- Anything unconfirmed shows no button: missing an outro is preferred to skipping story.
- Auto-skip (Settings → Playback → *Skip intro: Automatic*) applies to intros and recaps only; outros
  always wait for a press.

## Reuse (no repeated analysis)
- The neighbour compared with gets its markers too. The **next** episode is preferred, so when it's
  played next its markers are already there and nothing is fetched or analysed for it. Binge-watching
  analyses about every other episode.
- Results, including "searched, nothing found", are kept per profile (`skip-markers-<profile>.json`)
  while an episode is in progress, so resuming never repeats a search. Finished episodes' entries
  are deleted; untouched ones go after 30 days.
- The season's episode list is fetched once per detection and shared by every step, and kept 10 min
  for the next episode.

## Relevant files
`playback/SkipDetector.kt`, `playback/AudioFingerprint.kt`, `playback/CreditsVision.kt`,
`playback/PlayerController.kt` (merge, remember, `creditsRunToEnd`), `profiles/ProfileStores.kt`
(SkipMarkerStore), `ui/screens/player/PlayerChrome.kt` (buttons), platform `decodeImagePixels`
(`ImagePixelsAndroid.kt`, `ImagePixelsJvm.kt`).

## Performance considerations
Detection starts only after playback has started and never blocks it (real-time timeouts: AniSkip
8 s, audio 90 s, screen 60 s). Remembered markers appear before any request. The screen check runs only when the outro is still missing and the
server has trickplay for both episodes; it decodes ≤ 2 sheets per episode at half size.

## User interactions
Settings → Playback → *Skip intro* (button / automatic / off), *Find intros and outros automatically* (audio and
screen checks), *Find anime openings online* (AniSkip).

## Known limitations
- The screen check needs trickplay thumbnails (library setting *Enable trickplay image extraction*,
  Jellyfin 10.9+). Without them, live-action outros with changing music can't be found.
- Credits over the final scene, in colour, or on a non-black background aren't recognised (missed,
  never mis-skipped).
- Thumbnails are ~10 s apart, so an outro found on screen can start up to ~10 s late.
- Verified in CI on generated test episodes only; not yet on a real library (see [[Known Issues]]).

## Related notes
[[Video Player]] · [[Playback Issues]] · [[Server Overview]] · [[Change Log]]
