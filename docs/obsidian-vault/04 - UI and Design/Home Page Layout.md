---
tags: [design]
---
# Home Page Layout

```text
┌ sidebar (68 dp, 264 dp when open) ┬──────────────────────────────── profile ◯ ┐
│ ⌂ 🎬 📺 ▶ ★ 🔍 ▤ ⚙              │  Featured banner (80% of the screen height) │
│                                   │  title / logo · metadata · Play · Info       │
│                                   │  Brand tiles (optional)                      │
│                                   │  Continue Watching  ▭ ▭ ▭ ▭ ▭ (landscape)    │
│                                   │  Next Up            ▭ ▭ ▭ ▭ ▭                │
│                                   │  Because You Watched …  ▯ ▯ ▯ ▯ ▯ (posters) │
│                                   │  Recently Added in Movies ▯ ▯ ▯ ▯ ▯          │
│                                   │  Favorites          ▯ ▯ ▯ ▯ ▯                │
└───────────────────────────────────┴──────────────────────────────────────────────┘
```

- A `LazyColumn` (28 dp between rows). Each row: heading + `LazyRow` inside `RowScroll` with the
  screen's start padding, wrapped in `revealOnFocus()` so the heading is always shown with the
  focused card.
- Rows not loaded yet show a skeleton (or last session's items).
- The banner reveals itself fully when focused and settles to the top after 450 ms.

## Related notes
[[Home Page]] · [[Focus Management]] · [[UI Layout Issues]]
