---
tags: [feature]
---
# Episode Browser

## What it does
On a series details page: a season picker, a row of episode cards and, under the row, the focused
episode's name and description. In the player, the Episodes panel offers the same list.

## How it works
- Seasons from `/Shows/{id}/Seasons`, episodes from `/Shows/{id}/Episodes?seasonId=…`.
- Each card shows the episode image, its title and runtime, and a progress bar.
- **Description panel** (added in this update): a fixed-height area (`EPISODE_TEXT_HEIGHT`
  = 150 dp) under the row shows the focused episode's label, name and overview (up to 4
  lines, then "…"). Because its height never changes, moving between episodes doesn't shift the page.
  An episode without an overview shows "No description for this episode yet."
- The whole block is wrapped in `revealOnFocus()`, so focusing it scrolls the row *and* the text
  into view.

## Upcoming episodes and seasons (Nov 2026)
Episodes that haven't aired yet appear in the row with their air date, from the server's own
metadata (`GET /Shows/Upcoming?parentId=<series>`, names/numbers/dates only) and, when a Trakt key is
set, Trakt's next episode (exact UTC air time). See [[Details Page Layout]] for the dates' rules.
- **Episodes:** a card for each upcoming episode of the selected season the server doesn't have yet
  (or lists as a virtual item when the user shows missing episodes). The card is dimmed and Select
  does nothing, so it never looks playable. Under it, in soft yellow: "Airs 24 November 2026", or
  "Expected …" when the date is only a placeholder. The description panel reads
  "Episode 8 — Airs 24 November 2026" and "Not out yet." when there's no overview.
- **Seasons:** a season that hasn't started, with its first episode's date known, gets
  "Season 3 — Premieres 24 November 2026" in its chip (yellow part), or "— Expected 2027"; with no date
  it has no note (and a season the server doesn't list isn't added). A season known only from
  upcoming episodes is added after the others; choosing it shows those episodes without a request.
- The details page's "Next Episode" date and these come from the same list, so they always agree.

## Several episodes in one file
Files holding two or more episodes show one card per episode (or, when the server already lists
each, play only their own part). See [[Virtual Episode Splitting]]. A show can also be listed in another episode order (its DVD or intended order from TMDB, or one arranged by hand, across seasons too); the season tabs and rows then follow it. See [[Episode Orders]]. An episode's page has
**Split Episodes** / **Edit Split** to set where they start by hand.

## Relevant files
`data/AirDates.kt` (dates, `Upcoming`), `data/AirDateService.kt`, `ui/screens/Detail.kt`
(`withUpcoming`, `SeasonPicker`, `EpisodeRow`), `ui/components/Common.kt` (`AuroraChip` note).
`ui/screens/Detail.kt` (SeasonPicker, EpisodeRow), `ui/components/Cards.kt` (MediaCard),
`ui/screens/player/PlayerChrome.kt` (Episodes panel).

## Dependencies
[[Movies and TV Shows]] · [[Focus Management]]

## Data storage
None.

## Performance considerations
One request per season opened; the description reads data already in the episode list.

## User interactions
Left/Right moves between episodes (the panel follows); Up goes to the season picker; OK plays.

## Known limitations
Long overviews are cut at 4 lines.

## Future improvements
"More" to read a full overview.

## Related notes
[[Details Page Layout]] · [[UI Layout Issues]]
