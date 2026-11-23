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
- **Whose viewing counts:**
  - **Account profiles on this device:** their history from the server, each with its own sign-in.
  - **Device-only profiles:** their history stored on the device.
  - **Every other account on the server, when the signed-in account is a server administrator**
    (new): people who watch on other TVs or in a browser with their own accounts. Their history
    is read with the administrator's own sign-in (`/Users`, then each account's history); disabled
    accounts are left out. Anyone else's sign-in is refused `/Users`, and then only this device's
    profiles count.
- **What it counts:** time spent watching. A finished film or episode counts its length once per
  play; a started one counts how far it got. Episodes count towards their show.
- **Order:**
  1. The last 30 days, most time first. Ties go to the title watched more recently.
  2. If fewer than ten titles were watched lately, all-time viewing fills the remaining places.

## With little or no viewing
- **Fewer than three titles watched in the whole household: no row.** Home doesn't show an empty
  heading or a placeholder for it, and nothing is put in its place.
- **No stand-in ranking.** Ratings, "popular" lists and recently added titles aren't viewing, so
  the row never shows them as "most watched". Suggestions for a new profile live in
  "Recommended For You" instead ([[Recommendations]]).
- **The row appears by itself** once three titles have been watched: watching something marks the
  ranking out of date and Home's refresh after playback works it out again (see below).
- The app log says why when the row is left out: `Top10: Top 10 in Your Home: not enough watched
  yet (N title(s), needs 3), row not shown`.

## Why it "didn't load" (investigated November 2026)
- **No viewing yet** (the common case on a new server, or one whose other viewers use other
  devices): the row is left out by design. With administrator rights, viewing on other devices now
  counts.
- **It didn't notice new viewing.** `invalidate()` existed but nothing called it, so a ranking
  worked out before anything was watched (empty) stayed for up to 30 minutes, and Home kept the
  old row until it was reloaded. Now Home's refresh after watching invalidates it and builds a new
  Top 10 row (shown in place of the old one until it has loaded).
- **Wasteful history request.** It asked for the 300 most recently played items without a filter,
  so a profile that had watched nothing got 300 unwatched titles back. Now it's two small requests:
  finished titles (`IsPlayed`, up to 300) and ones in progress (`IsResumable`, up to 50).
- **Navigation:** while it was loading, or after loading empty, the row took part in the gap that
  stopped Up from reaching the rows above (see [[Fixed Issues]] → Home navigation). Empty rows are
  no longer laid out at all.

## Privacy and parental controls
The ranking uses everyone's viewing, but the titles are then fetched as the profile that's looking.
Anything that profile can't see on the server (another library, a title above its parental rating)
never appears; the next title moves up instead. No times or names of who watched are shown.

## Performance
- **Requests:** two per account (finished titles and ones in progress, no images) plus `/Users`
  (once per ranking), plus one for the titles shown. Device-only profiles cost nothing.
- **When:** worked out at most every 30 minutes per server, and again after something is watched
  but no more than once a minute (`REFRESH_MS`). If the ranking hasn't changed, the row from last
  time is reused without asking for the titles again. It loads like any other row: when it comes on
  screen, without holding up the rest of Home.
- **Next launch:** the last row is kept with Instant Home and shown at once.
- **Internet:** none needed.

## Limitations
- **Without administrator rights, only this device's profiles count.** Jellyfin only lets an
  administrator read other accounts' history.
- **Recorded plays only:** played counts come from Jellyfin's play count. A film marked watched
  without being played counts its length once.

## Code
- `recommend/HouseholdTop10.kt`: `rank`, `fromServer`, `fromLocal`, `top` (with its cache).
- `JellyfinApi.watchHistory` (the history requests, `forUser` for another account) and
  `JellyfinApi.serverUsers` (`/Users`).
- `SessionManager.household`: the profiles on a server, as clients and local histories.
- `LocalWatchStore.history`: a device-only profile's history.
- `ui/components/Common.kt` `RankedRail`: the row with its numerals. `Home.kt`: the row's place
  (`top10`).
- **Tests:** `commonTest/HouseholdTop10Test.kt` (household, administrator scope, no history, refresh
  after watching), and the emulator check "Top 10 in Your Home" in
  `ci/android_e2e.sh` (screenshots `android-40/41-home-top10`).

Related: [[Home Page]], [[Watch History]], [[Recommendations]].
