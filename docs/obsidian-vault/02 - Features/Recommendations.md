---
tags: [feature, performance]
---
# Recommendations

## What it does
Home rows **"Because You Watched <title>"** and **"Recommended For You"**, personal to each profile.

## How it works
- `RecommendationService.plan()` makes **one** history request (recently finished movies/episodes,
  `IsPlayed`, newest first) and combines it with Continue Watching. Series genres come from one
  extra `ids=` request.
- `RecommendationEngine` weighs titles (newer, favourites, thumbs-up and own ratings count more;
  thumbs-down counts against; long-watched series count more) and matches genres.
- Candidates are fetched **server-side filtered**: unwatched titles in the right genres
  (`IsUnplayed`, sorted by community rating), plus `/Items/{id}/Similar` for "Because You Watched".
- Finished rows are saved per profile with a **signature** of the history; while the history is
  unchanged, launches reuse them and cost only the history request.

## Relevant files
`recommend/Recommendations.kt`, `ui/screens/Home.kt` (recommendationRails).

## Dependencies
[[Watch History]] · [[Home Page]] · [[API Endpoints]]

## Data storage
`recommendations-<profile>.json` (rows + signature).

## Performance considerations
No catalogue download (the search index is no longer used for this); Home waits for the history at
most 600 ms and merges the rows in later if it's slower.

## User interactions
Ordinary rows; they refresh after something is watched.

## Known limitations
Genre-based only; a library with sparse genre metadata gets weaker suggestions.

## Future improvements
Use people (actors/directors) as a second signal.

## Related notes
[[Home Page]] · [[User Profiles]] · [[Caching Strategy]]
