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

## Related notes
[[Movies and TV Shows]] · [[UI Layout Issues]] · [[Frosted Glass Effects]]
