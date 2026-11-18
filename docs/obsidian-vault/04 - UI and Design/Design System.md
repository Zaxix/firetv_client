---
tags: [design]
---
# Design System

Defined in `ui/theme/Theme.kt` (`Aurora.colors`, `AuroraType`, `Aurora.motion`). The look is
deliberately preserved between updates: dark, tvOS-like, frosted glass, large bold titles.

## Colours (dark theme)
| Role | Value |
|---|---|
| background | `#000000` |
| surface / surfaceRaised | `#1C1C1E` / `#2C2C2E` |
| surfaceGlass | `#1C1C1E` at 70% |
| onBackground / muted / faint | `#F4F5F7` / `#B4B8C2` / `#7A7F8C` |
| focusRing | white |
| accent | `#8AB4FF` (or the focused artwork's accent when *Artwork accents* is on) |
| error | `#FF6B6B` |

## Type (Inter, SIL OFL)
hero 48 sp bold · title 32 bold · section 22 bold · tab 17 semibold · body 16 · label 15 semibold ·
caption 13 medium. Headings use slightly negative tracking.

## Motion
`Aurora.motion` scales every duration by Settings → *Animations* (Full / Reduced / Off). Focus
scale: 1.10 (Full), 1.05 (Reduced), 1.0 (Off).

## Sound
Two subtle original sounds; see [[UI Sounds]].

## Assets
Original only: gradient avatars, generated sounds, BlurHash-based backdrops, brand names in the
app's own type. No third-party logos ship with the app.

## Related notes
[[Frosted Glass Effects]] · [[Animations and Transitions]] · [[Home Page Layout]]
