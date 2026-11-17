---
tags: [bug]
---
# Known Issues

Open issues and limits, stated plainly. (The full list of platform limits is in
`docs/KNOWN_LIMITATIONS.md`.)

- **Not tested on real Fire TV hardware** in this update: navigation, sounds, profiles and timings
  were checked on the Android TV emulator and the desktop build in CI only.
- Subtitle delay isn't available on Android.
- Dolby Vision / HDR output unverified on real displays.
- Device-only profiles: kept on one device; server dashboards show the account's name; no
  per-profile thumbs/star ratings; a series isn't auto-marked watched when all episodes are
  ([[User Profiles]]).
- Recommendation rows may appear a moment after the others on a slow server (by design).
- Skip detection by audio needs a neighbouring episode with the same music.

## Related notes
[[Fixed Issues]] · [[Future Improvements]]
