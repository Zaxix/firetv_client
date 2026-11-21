---
tags: [bug]
---
# Known Issues

Open issues and limits, stated plainly. (The full list of platform limits is in
`docs/KNOWN_LIMITATIONS.md`.)

- **Not tested on real Fire TV hardware** in this update: navigation, sounds, profiles and timings
  were checked on the Android TV emulator and the JVM test host in CI only.
- Subtitle delay isn't available with ExoPlayer (it is with the optional mpv player).
- Dolby Vision / HDR output unverified on real displays.
- Device-only profiles: kept on one device; server dashboards show the account's name; no
  per-profile thumbs/star ratings; a series isn't auto-marked watched when all episodes are
  ([[User Profiles]]).
- Recommendation rows may appear a moment after the others on a slow server (by design), and with
  thin metadata (no descriptions/tags) or a small library they're often short or absent — by design
  ([[Recommendations]]).
- **Outro detection** ([[Skip Markers]]):
  - by audio needs a neighbouring episode with the same closing music;
  - on screen needs the server's trickplay thumbnails, and only recognises light, colourless text on
    black (credits over a scene, in colour or on a coloured background are missed, not mis-skipped);
  - an on-screen outro can start up to ~10 s late (thumbnail spacing);
  - verified on generated test episodes in CI; not yet confirmed on your real live-action shows.
- **Audio default** (English except anime) depends on the metadata saying a show is anime: an anime
  without an anime genre/tag, Japanese title or Japanese origin is treated as non-anime and gets the
  English dub when the file has one ([[Video Player]]).
- **Episode Orders** ([[Episode Orders]]):
  - automatic orders need a TMDB key and a library in TMDB's (aired) numbering;
  - no real TMDB request has been tested (CI simulates TMDB's answers);
  - for an account profile, Home's Next Up only lists shows the server already has there;
  - a show's first automatic order reloads its page's list once when found.
- **Virtual Episode Splitting** ([[Virtual Episode Splitting]]):
  - several server entries sharing one file can't be produced on the CI server, so that case is
    covered by unit tests only;
  - a part's progress lives on this device and profile, so other Jellyfin apps see only the file's
    watched mark, and the server's "Now playing" doesn't show virtual episodes;
  - later parts have no description, and their thumbnail needs chapter images on the server;
  - the split editor hasn't been tried with a real remote.
- **Film subtitle fonts** ([[Subtitles]]):
  - only the Harry Potter films have one;
  - they're matched by TMDB collection 1241 or the title, so a film missing both keeps the usual font;
  - the settings sample doesn't show the film font;
  - not seen on a real Fire TV screen.
- **Upcoming air dates** ([[Details Page Layout]]):
  - they appear only when the server's metadata lists future episodes (virtual items) or a Trakt
    key is set. Many servers don't create virtual episodes, and then nothing is shown (never a
    guessed date);
  - that `/Shows/Upcoming?parentId=<series>` narrows to the series is assumed from Jellyfin's
    behaviour and guarded by a client-side series filter. CI's server has no future episodes, so only
    "the request works and nothing is invented" is verified; the yellow UI hasn't been seen with
    real data.
- **Skip markers, accuracy**: edge placement was tuned on synthetic audio (a Python replica plus CI's
  generated episodes). Real themes may leave up to ~1.4 s unskipped at an edge; skipping story is what
  the tuning avoided, but it's unconfirmed on a real library.
- **Subtitles**: a track whose title says nothing useful and that is flagged forced is taken for
  Signs & Songs (it's ranked after the dialogue tracks, still used when it's the only one).
- **Temporary files**: an expired saved file start is deleted at the next app start, not exactly at
  5 days; the 5 days follow the device clock ([[Temporary File Lifecycle]]).

## Related notes
[[Fixed Issues]] · [[Future Improvements]]
