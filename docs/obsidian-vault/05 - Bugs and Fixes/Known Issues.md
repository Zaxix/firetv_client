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
- **Temporary files**: an expired saved file start is deleted at the next app start, not exactly at
  5 days; the 5 days follow the device clock ([[Temporary File Lifecycle]]).

## Related notes
[[Fixed Issues]] · [[Future Improvements]]
