---
tags: [design, performance]
---
# Frosted Glass Effects

## What it is
The soft, colour-matched glass behind screens: the focused card's colours, blurred and dimmed.

## How it works
- `FrostedField(hash)` takes the artwork's **BlurHash** (a 32×18 image the server already sends with
  the item, so nothing is downloaded), bakes the frosting (dimming towards the edges, lighter middle)
  into those few hundred pixels, and lets the GPU stretch it over the screen: **one full-screen
  draw**, which matters on Fire TV sticks.
- `ShellBackdrop` follows the focused card through `LocalBackdrop`, settling 120 ms after focus stops
  moving and cross-fading over 350 ms, so fast scrolling doesn't start a fade per card.
- Home and details pages draw their own backdrop; the shell skips its hidden layer there.
- `Glass` provides the light edge and sheen on panels (sidebar, prompts, the profile editor).
- `FrostCache` keeps recently frosted fields so moving back and forth is free.

## Files
`ui/components/Frosted.kt`, `ui/theme/BlurHash.kt`, `ui/App.kt` (ShellBackdrop use).

## Settings
Appearance → *Backdrops*, *Frosted background*, *Artwork accents*.

## Related notes
[[Design System]] · [[Performance Optimization]]
