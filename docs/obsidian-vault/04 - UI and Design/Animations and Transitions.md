---
tags: [design]
---
# Animations and Transitions

All durations go through `Aurora.motion.duration(ms)`, which shortens or removes them for *Reduced*
/ *Off*.

| Animation | Spec | Where |
|---|---|---|
| Focus lift | scale 1.10, 180 ms | `FocusSurface` (`graphicsLayer`, no relayout) |
| Screen change | fade in 220 ms / out 120 ms | `MainShell` `AnimatedContent` |
| Sidebar open/close | width 68 → 264 dp + scrim, 220 ms | `SideBar` |
| Backdrop colours | 120 ms settle, 350 ms cross-fade | `ShellBackdrop` |
| Exit hint | fade 150 / 250 ms | `MainShell` |
| Player controls, panels | fades/slides | `PlayerChrome` |

## Related notes
[[Design System]] · [[Frosted Glass Effects]]
