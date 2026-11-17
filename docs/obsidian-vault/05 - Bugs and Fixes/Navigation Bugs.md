---
tags: [bug]
---
# Navigation Bugs

History of focus/navigation problems and how they're prevented now.

- **Right at the end of a row went to the profile button.** Cause: Compose's geometric search.
  Fix: `FocusRegions` — content edges stay put; the header is reachable only via Up.
- **Right out of the sidebar landed on a random card.** Fix: the sidebar remembers the card that had
  focus and returns to it.
- **First card under the sidebar after right-then-left.** Fix: `RowScroll` (see [[UI Layout Issues]]).
- **Holding a direction made a buzz of sounds.** Prevented by design: one tick per key press, 45 ms
  minimum gap ([[UI Sounds]]).

CI checks (emulator): navigation steps with screenshots, first card x = row start after
right-then-left, row heading top ≥ 0 after moving up, details hero at item 0 offset 0.

## Related notes
[[Focus Management]] · [[Navigation Behavior]] · [[Testing Guide]]
