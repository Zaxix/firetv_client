---
tags: [feature]
---
# Movies and TV Shows

## What it does
Browsing: the **Movies** and **TV Shows** sections, libraries, Brand Hubs (titles from one studio or
network), and the **details page** for every movie, series and episode.

## How it works
- Sections and library grids are `Browse.kt` / `Library.kt`, paging through `/Items` with
  `ItemQuery` (sort, filters, genres, studios).
- The details page (`Detail.kt`) shows the hero (backdrop, logo or title, metadata, ratings),
  buttons (Play/Resume, From start, Trailer, Favourite, Watched, Go to Series), the
  [[Episode Browser]] for series, cast, "More like this" (`/Items/{id}/Similar`) and reviews
  ([[Ratings]]).
- Brand tiles on Home lead to Brand Hubs (`BrandService`, rows load in parallel and are cached
  10 min; brand names are drawn in the app's own type, no third-party logos).

## Relevant files
`ui/screens/Browse.kt`, `ui/screens/Library.kt`, `ui/screens/Detail.kt`, `ui/screens/Brands.kt`,
`brands/Brands.kt`, `jellyfin/JellyfinApi.kt`, `jellyfin/ImageUrls.kt`.

## Dependencies
[[API Endpoints]] · [[Focus Management]] · [[Details Page Layout]]

## Data storage
None beyond the image cache ([[Caching Strategy]]).

## Performance considerations
Library grids request only card fields (`Fields.CARD`) and one image type; details load extras
(similar, ratings) after the page is on screen.

## User interactions
D-pad through grids; OK opens; Back returns to the exact card (see [[Navigation System]]).

## Known limitations
Live TV, music, photos aren't implemented.

## Future improvements
Collections browsing with their own pages.

## Related notes
[[Episode Browser]] · [[Search]] · [[Details Page Layout]]
