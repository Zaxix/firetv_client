---
tags: [feature]
---
# App Updates

`UpdateManager` checks the repository's rolling `latest-build` release (at most daily, or on demand;
Settings → About). Only builds whose CI run fully passed are offered; files are checked against the
SHA-256 in `version.json`; Android also checks the APK's package and signing key, and the system
installer always asks to confirm (on Fire OS the first update also asks to allow *Install unknown
apps*). Debug APKs open the download page instead of installing. The real Android install screen
isn't exercised by CI. After an update is installed, the downloaded APK (tens of MB) is deleted
from the cache at the next start (`AndroidUpdater.deleteInstalledUpdate`).

Files: `update/Updates.kt`, `androidMain/.../AndroidUpdater.kt`,
`ui/screens/UpdateUi.kt`.

## Related notes
[[Build and Run]] · [[Testing Guide]]
