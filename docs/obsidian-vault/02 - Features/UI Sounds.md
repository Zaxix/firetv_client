---
tags: [feature]
---
# UI Sounds

## What it does
Subtle sounds: a short **tick** when a directional press moves focus, and a soft **chime** when
something is selected. Settings → Appearance → **Navigation sounds** (on/off) and **Sound volume**
(25/50/75/100%), saved per profile.

## How it works
- Two original sounds generated for Sharkfin and bundled in the app
  (`composeResources/files/ui_move.wav`, 28 ms; `ui_select.wav`, 130 ms; ~7 KB together).
- The root key handler calls `UiSounds.noteDirectionalKey()` on an arrow key press;
  `FocusSurface` calls `UiSounds.focusMoved()` when it gains focus. A tick plays only when both
  happen within 350 ms, so **one press = at most one tick**, a press that goes nowhere (Right at a
  row's end) is silent, and focus moved by the app itself (restoring focus) is silent.
- `FocusSurface` clicks call `UiSounds.selected()`.
- At most one sound every 45 ms (holding a key doesn't buzz); each new sound stops the previous one
  (no overlap).
- `MainShell` sets `UiSounds.suppressed = true` while the player is open: **no sounds during
  playback**.
- Android: `SoundPool` (2 streams, `USAGE_ASSISTANCE_SONIFICATION`, so system volume/mute apply).
  Windows: `javax.sound` `Clip` with gain.

## Relevant files
`ui/sound/UiSounds.kt`, `androidMain/.../AndroidUiSounds.kt`, `desktopMain/.../DesktopUiSounds.kt`,
`ui/components/Focus.kt` (FocusSurface), `ui/App.kt`, `settings/Settings.kt`,
`ui/screens/Settings.kt`.

## Dependencies
[[Navigation System]] · [[Focus Management]]

## Data storage
`navigationSounds`, `uiSoundVolume` in the profile's settings file.

## Performance considerations
Sounds are decoded once at start into SoundPool / Clip; playing one is a single native call.

## User interactions
Toggle and volume in Settings → Appearance.

## Known limitations
Not tested on real Fire TV audio hardware (CI checks counts via logs: 3 moves = 3 ticks,
OK = 1 chime, none in the player, none when off).

## Related notes
[[Change Log]] · [[Design System]]
