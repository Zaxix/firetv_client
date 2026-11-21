---
tags: [feature, home]
---
# Top 10 in Your Home

## What it does
A row on Home, after Continue Watching and Next Up, of the ten films and shows watched most in the
household. It's laid out like a streaming service's top-ten row:
- each poster stands in front of its place, drawn as a tall numeral (dark fill, light grey outline)
  about as high as the poster;
- each poster covers the right edge of its numeral;
- there are no titles under the posters, so the numbers carry the row.

Films and shows share the one row.

## What "most watched" means
- **Whose viewing counts:** every profile this device knows on the current server.
  - **Account profiles:** their history from the server, using each one's own sign-in.
  - **Device-only profiles:** their history stored on the device.
- **What it counts:** time spent watching. A finished film or episode counts its length once per
  play; a started one counts how far it got. Episodes count towards their show.
- **Order:**
  1. The last 30 days, most time first. Ties go to the title watched more recently.
  2. If fewer than ten titles were watched lately, all-time viewing fills the remaining places.
- **When the row shows:** fewer than three titles watched in total means no row.

## Privacy and parental controls
The ranking uses everyone's viewing, but the titles are then fetched as the profile that's looking.
Anything that profile can't see on the server (another library, a title above its parental rating)
never appears; the next title moves up instead. No times or names of who watched are shown.

## Performance
- **Requests:** one per account profile (its last 300 things played, no images), plus one for the
  titles shown. Device-only profiles cost nothing (their history is on the device).
- **When:** worked out at most every 30 minutes per server. The row loads like any other: only
  when it comes on screen, without holding up the rest of Home.
- **Next launch:** the last row is kept with Instant Home and shown at once.
- **Internet:** none needed.

## Limitations
- **Only this device's profiles count:** Jellyfin users who never signed in on this TV don't count.
  Jellyfin only lets a server admin read other users' history, so their viewing isn't available.
- **Recorded plays only:** played counts come from Jellyfin's play count. A film marked watched
  without being played counts its length once.

## Code
- `recommend/HouseholdTop10.kt`: `rank`, `fromServer`, `fromLocal`, `top` (with its cache).
- `JellyfinApi.watchHistory`: the account history request.
- `SessionManager.household`: the profiles on a server, as clients and local histories.
- `LocalWatchStore.history`: a device-only profile's history.
- `ui/components/Common.kt` `RankedRail`: the row with its numerals. `Home.kt`: the row's place
  (`top10`).
- **Tests:** `commonTest/HouseholdTop10Test.kt`, and the emulator check "Top 10 in Your Home" in
  `ci/android_e2e.sh` (screenshots `android-40/41-home-top10`).

Related: [[Home Page]], [[Watch History]], [[Recommendations]].
