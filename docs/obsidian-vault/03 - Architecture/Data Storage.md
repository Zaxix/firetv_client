---
tags: [architecture]
---
# Data Storage

| What | Where | Notes |
|---|---|---|
| Servers, users, profiles | `servers.json` | No secrets (a test asserts this) |
| Access tokens | Secure vault: Android Keystore (AES-256-GCM) / Windows DPAPI | Key `token:<server>:<user>`; device profiles add none |
| Per-profile data | `<base>-<profile key>.json` | `settings`, `track-memory`, `ratings`, `skip-markers`, `search-history`, `recommendations`, `watch` (`ProfileFiles.ALL`) |
| Home saved copy | `home-cache-<server>-<user>[-p<profile>].json` | Last rows + banner |
| Artwork | Coil disk cache `artwork/` (300 MB) + memory (20% of app memory) | |
| MKV headers (Android) | Media3 SimpleCache, 256 MB LRU | Headers only, never video |
| Log | `aurora.log` (desktop) / logcat | Tokens, keys and passwords redacted by `Log.redact` |

File locations: Android app-private storage; Windows `%APPDATA%\AuroraTV` (data) and
`%LOCALAPPDATA%\AuroraTV\Cache` (caches).

A **profile key** is `<first 12 chars of server id>-<user id>` plus `-p<id>` for a device-only
profile. Removing a profile deletes all its files.

## Related notes
[[Caching Strategy]] · [[User Profiles]]
