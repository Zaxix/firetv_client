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

## Relevant files
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
