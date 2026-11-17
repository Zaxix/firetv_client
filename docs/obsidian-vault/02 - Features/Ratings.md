---
tags: [feature]
---
# Ratings

## What it does
Scores and reviews on details pages: the server's critic score, plus (with free keys) Rotten
Tomatoes critics/audience, IMDb, Metacritic and Letterboxd via **MDBList**, and spoiler-filtered
reviews via **Trakt**.

## How it works
`RatingsService` fetches after the page is on screen, with an 8 s budget per call; results are cached
per session (failures for 5 min) and kept per profile for a week (`RatingsStore`, compact strings).
Keys live in the secure vault and are mirrored into the user's Jellyfin DisplayPreferences
(`aurora-ratings`) so a key typed on a PC appears on the TV. Keys go only to their own service and
are never logged.

## Relevant files
`ratings/Ratings.kt`, `ui/screens/Ratings.kt`, `profiles/ProfileStores.kt` (RatingsStore),
`ui/screens/Settings.kt` (Ratings page).

## Dependencies
[[Details Page Layout]] · [[Data Storage]]

## Data storage
`ratings-<profile>.json`, keys in the vault.

## Performance considerations
Nothing extra hits the Jellyfin server except the one DisplayPreferences read/write for keys.

## User interactions
The reviews row scrolls sideways; focusing it reveals it fully ([[Focus Management]]).

## Known limitations
IMDb user reviews aren't available (no API). Episodes show the server's ratings only.

## Future improvements
—

## Related notes
[[Movies and TV Shows]]
