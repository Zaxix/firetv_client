---
tags: [bug]
---
# UI Layout Issues

## First card clipping into the sidebar (fixed)
Rows are full-width (they scroll underneath the collapsed sidebar), with the first card placed at the
screen's start padding. Compose's default `BringIntoViewSpec` only keeps a focused item a little
inside the *visible* area, so coming back left from the end of a row it stopped scrolling as soon as
the first card was partly visible, under the sidebar. `RowScroll` replaces the rule per row: a card
must lie between the row's start padding and its end padding; for the first card that means the row
returns exactly to offset 0. This fixes the positioning itself; nothing is hidden or clipped.

## Headings and hero disappearing when moving up (fixed)
`revealOnFocus()` asks the scroll container for the whole element (row + heading, hero, episode +
description), and asks again 260 ms later. The details hero additionally returns to the top.

## Episode text (fixed)
Fixed-height description panel, so focus changes don't move anything.

## Related notes
[[Focus Management]] · [[Fixed Issues]] · [[Home Page Layout]] · [[Details Page Layout]]
