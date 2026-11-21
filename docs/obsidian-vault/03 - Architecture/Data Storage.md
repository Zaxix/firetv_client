---
tags: [architecture]
---
# Data Storage

| What | Where | Notes |
|---|---|---|
| Servers, users, profiles | `servers.json` | No secrets (a test asserts this) |
| Access tokens | Secure vault: Android Keystore (AES-256-GCM) | Key `token:<server>:<user>`; device profiles add none |
| Per-profile data | `<base>-<profile key>.json` | `settings`, `track-memory`, `ratings`, `skip-markers`, `search-history`, `recommendations`, `watch`, `split-watch` (`ProfileFiles.ALL`) |
| Episode splits | `episode-splits-<server>.json` | Which files hold several episodes and where each starts; shared by the server's profiles ([[Virtual Episode Splitting]]) |
| Film subtitle fonts | In the app (`composeResources/files/subtitle-fonts/`), copied once to `cache/subtitle-fonts/` (Media3) or `cache/mpv-fonts/` (mpv) | [[Subtitles]] |
| Home saved copy | `home-cache-<server>-<user>[-p<profile>].json` | Last rows + banner |
| Artwork | Coil disk cache `artwork/` (300 MB) + memory (20% of app memory) | |
| MKV headers | Media3 SimpleCache `media-headers/`, 256 MB cap | Headers only, never video; lifecycle in [[Temporary File Lifecycle]] |
| Temporary-file ledger | `temp-files.json` | Which profile holds which saved file start, until when |
| Log | logcat | Tokens, keys and passwords redacted by `Log.redact` |

File locations: Android app-private storage (`files/` for data, `cache/` for caches).

A **profile key** is `<first 12 chars of server id>-<user id>` plus `-p<id>` for a device-only
profile. Removing a profile deletes all its files.

## Related notes
[[Caching Strategy]] · [[User Profiles]]
