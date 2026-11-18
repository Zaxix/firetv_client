---
tags: [design]
---
# Details Page Layout

From the top: **hero** (full-bleed backdrop with the page's own frosted background, logo or title,
metadata line, ratings, overview, buttons), then for series the **season picker**, **episode row**
and the **episode description panel**, then **cast**, **More like this** and **reviews**.

- The background design is unchanged by this update.
- The hero is a `LazyColumn` item with `revealOnFocus()`; when it regains focus the list returns to
  item 0, offset 0 (after 450 ms), so the hero is fully visible again after scrolling down.
- Episode description: fixed 150 dp panel under the episode row (see [[Episode Browser]]); cards
  themselves stay compact (title + runtime).
- Horizontal rows (seasons, episodes, cast, similar, reviews) use `RowScroll`.

## Next episode air date (Nov 2026)
The metadata line of a continuing series reads "Continuing — Next Episode: 24 November 2026", with
the "Next Episode" part in a soft yellow (`AiringYellow` #F2C94C, quieter than the player's skip
yellow). Rules (`data/AirDates.kt`, `Upcoming`):
- **Only a real date, never an aired episode.** Only episodes the server doesn't have yet (virtual
  items) with an air date still ahead; nothing is estimated. An ended series asks for nothing.
- **Time zones.** Jellyfin stores a provider's air date as midnight UTC; that's a *calendar date* and is
  shown as is (converting it would show the day before in the Americas), and it counts as upcoming
  from tomorrow on, since its time of day is unknown. An exact time (Trakt's `first_aired`, or a server
  time that isn't midnight) is converted to the device's time zone and is upcoming until that moment;
  for the same episode it replaces the server's calendar date.
- **Tentative vs confirmed.** A 1 January date (a year placeholder) shows as "expected 2027"; an episode
  named "TBA"/"TBD"/"Episode N" or unnamed shows "expected 24 November 2026". Otherwise the date is
  shown plainly.
- **Never in the way.** The dates load after the page is up (the page doesn't wait), from memory when the
  series was opened in the last 6 hours (`AirDateService`, 50 series, re-checked against the clock
  on every read), else one small request (plus Trakt when a key is set). Two pages opening at once
  share the request.

Tests: `AirDatesTest` (time zones New York/Tokyo/UTC, aired today/earlier, placeholders, virtual vs
in-library, season premieres, Trakt merge, date arithmetic).

## Related notes
[[Movies and TV Shows]] · [[UI Layout Issues]] · [[Frosted Glass Effects]]
