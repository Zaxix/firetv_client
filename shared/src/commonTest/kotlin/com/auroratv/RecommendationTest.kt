package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.UserItemData
import com.auroratv.recommend.RecommendationEngine
import com.auroratv.recommend.Similarity
import com.auroratv.recommend.WatchedTitle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The content-based ranking behind "Because You Watched" and "Recommended For You". */
class RecommendationTest {
    private fun movie(id: String, vararg genres: String, rating: Double? = null, played: String? = null, likes: Boolean? = null, fav: Boolean = false) =
        BaseItemDto(id = id, name = id, type = "Movie", genres = genres.toList(), communityRating = rating,
            userData = played?.let { UserItemData(played = true, lastPlayedDate = it, likes = likes, isFavorite = fav) })
    private fun series(id: String, vararg genres: String) = BaseItemDto(id = id, name = id, type = "Series", genres = genres.toList())
    private fun episode(id: String, seriesId: String, played: String) =
        BaseItemDto(id = id, name = id, type = "Episode", seriesId = seriesId, seriesName = "Show $seriesId", userData = UserItemData(played = true, lastPlayedDate = played))

    @Test
    fun episodesCountForTheirSeriesNewestFirst() {
        val titles = RecommendationEngine.watchedTitles(listOf(
            movie("m1", played = "2026-11-01T10:00:00Z"),
            episode("e1", "s1", "2026-11-10T10:00:00Z"),
            episode("e2", "s1", "2026-11-09T10:00:00Z"),
            episode("e3", "s1", "2026-11-08T10:00:00Z"),
        ))
        assertEquals(listOf("s1", "m1"), titles.map { it.id })
        assertEquals("Show s1", titles[0].name)
        assertTrue(titles[0].isSeries)
        assertTrue(titles[0].weight > titles[1].weight, "newer, and three episodes: a stronger signal")
    }

    @Test
    fun thumbsDownIsNeverASeedAndPullsItsGenresDown() {
        val titles = RecommendationEngine.watchedTitles(listOf(
            movie("liked", "Comedy", played = "2026-11-02T00:00:00Z", likes = true),
            movie("hated", "Horror", played = "2026-11-03T00:00:00Z", likes = false),
        ))
        assertTrue(titles.first { it.id == "hated" }.disliked)
        val genres = mapOf("liked" to listOf("Comedy"), "hated" to listOf("Horror"))
        val profile = RecommendationEngine.genreProfile(titles) { genres[it].orEmpty() }
        assertEquals(setOf("Comedy"), profile.keys, "a disliked genre gets no weight")
    }

    @Test
    fun forYouFollowsTheProfilesGenres() {
        // Profile A watches crime/thrillers; profile B comedy/animation: different picks from the same library.
        val library = listOf(
            movie("heist", "Crime", "Thriller", rating = 7.0), movie("noir", "Crime", "Drama", rating = 7.5),
            movie("toon", "Animation", "Comedy", rating = 8.0), movie("sitcom", "Comedy", rating = 6.0),
            movie("docu", "Documentary", rating = 9.0),
        )
        val a = RecommendationEngine.rankForProfile(library, mapOf("Crime" to 1.0, "Thriller" to 0.8), 3).map { it.id }
        val b = RecommendationEngine.rankForProfile(library, mapOf("Comedy" to 1.0, "Animation" to 0.7), 3).map { it.id }
        assertEquals(listOf("heist", "noir"), a, "only titles that match, best match first")
        assertEquals(listOf("toon", "sitcom"), b)
        assertTrue(a.none { it in b })
    }

    @Test
    fun becauseYouWatchedAddsTheOtherKindByGenre() {
        val shows = listOf(series("crimeShow", "Crime", "Drama"), series("cartoon", "Animation"), series("halfMatch", "Drama", "Romance", "Music", "History"))
        val picks = RecommendationEngine.byGenreOverlap(listOf("Crime", "Drama"), shows, 5).map { it.id }
        assertEquals(listOf("crimeShow"), picks, "needs a real overlap (a third of the genres), not just one shared tag")
    }

    @Test
    fun interleavingKeepsOrderAndDropsRepeats() {
        val p = listOf(movie("a"), movie("b"), movie("c"), movie("d"))
        val s = listOf(series("x"), movie("b"), series("y"))
        assertEquals(listOf("a", "b", "x", "c", "d", "y"), RecommendationEngine.interleave(p, s).map { it.id })
    }

    // ---- Content similarity ("Because You Watched") ----------------------------------------------

    private fun show(id: String, genres: List<String>, overview: String, rating: String? = null, original: String? = null, country: String? = null, score: Double = 7.5) =
        BaseItemDto(id = id, name = id, type = "Series", genres = genres, overview = overview, officialRating = rating,
            originalTitle = original, productionLocations = listOfNotNull(country), communityRating = score)

    private val charlotte = show("Charlotte", listOf("Animation", "Comedy", "Drama", "Sci-Fi & Fantasy"),
        "Yuu Otosaka uses his ability to briefly possess others to cheat at school, until a student council of teenagers with special abilities enrols him at their academy.",
        "TV-14", "シャーロット", "Japan")

    @Test
    fun becauseYouWatchedAnAnimeMeansAnimeWithSimilarStories() {
        val library = listOf(
            show("The Inbetweeners", listOf("Comedy"), "Four awkward school friends try to fit in at their suburban sixth form college.", "TV-MA", country = "United Kingdom", score = 8.3),
            show("Adventure Time", listOf("Animation", "Comedy", "Sci-Fi & Fantasy", "Kids"), "Finn the human and Jake the dog have magical adventures in the Land of Ooo.", "TV-PG", country = "United States of America", score = 8.6),
            show("Angel Beats!", listOf("Animation", "Comedy", "Drama", "Sci-Fi & Fantasy"), "In an afterlife school, students with special abilities fight against a girl the student council calls Angel.", "TV-14", "エンジェルビーツ", "Japan"),
            show("Erased", listOf("Animation", "Mystery", "Drama", "Sci-Fi & Fantasy"), "A man with the ability to travel back in time returns to his school days to stop a kidnapping.", "TV-14", "僕だけがいない街", "Japan"),
            show("A Cooking Anime", listOf("Animation", "Comedy"), "Two chefs compete to run the best ramen shop in town.", "TV-PG", "ラーメン", "Japan", score = 9.0),
            show("Breaking Bad", listOf("Drama", "Crime"), "A chemistry teacher turns to cooking methamphetamine.", "TV-MA", country = "United States of America", score = 9.5),
        )
        val picks = Similarity.rank(charlotte, library, emptySet(), 10).map { it.id }
        assertEquals(listOf("Angel Beats!", "Erased"), picks, "anime with abilities, school and drama; never the live-action comedy or the kids' cartoon")
        assertTrue("A Cooking Anime" !in picks, "anime alone isn't a reason (it shares only Comedy)")
    }

    @Test
    fun kindsAudiencesAndReasons() {
        val sitcom = show("Sitcom", listOf("Comedy"), "Flatmates argue about rent in London.", "TV-14", country = "United Kingdom")
        val other = show("Another sitcom", listOf("Comedy"), "Two flatmates in London share a tiny flat and argue about rent and girlfriends.", "TV-14", country = "United Kingdom")
        val cartoon = show("Cartoon", listOf("Animation", "Comedy"), "Two flatmates in London share a tiny flat and argue about rent.", "TV-Y7", country = "United States of America")
        val picks = Similarity.rank(sitcom, listOf(other, cartoon), emptySet(), 5).map { it.id }
        assertEquals(listOf("Another sitcom"), picks, "live-action comedy gets live-action comedy; a kids' cartoon with the same plot words doesn't make it")
        assertEquals(1.0, Similarity.kindFactor(com.auroratv.playback.ContentKind.Anime, com.auroratv.playback.ContentKind.Anime))
        assertTrue(Similarity.kindFactor(com.auroratv.playback.ContentKind.Anime, com.auroratv.playback.ContentKind.Animation) < 0.5)
        // No shared genre, tag or description: never recommended, however well rated.
        assertTrue(Similarity.rank(sitcom, listOf(show("Nature", listOf("Documentary"), "Whales migrate across oceans.", score = 9.9)), emptySet(), 5).isEmpty())
        // Missing metadata is handled: no description, no rating.
        assertTrue(Similarity.rank(sitcom, listOf(BaseItemDto(id = "bare", type = "Series")), emptySet(), 5).isEmpty())
    }

    @Test
    fun forYouFollowsTheKindsAProfileWatches() {
        val titles = listOf(WatchedTitle("Charlotte", "Charlotte", true, 1.0), WatchedTitle("Erased", "Erased", true, 0.85))
        val details = mapOf("Charlotte" to charlotte, "Erased" to show("Erased", listOf("Animation", "Mystery", "Drama"), "Time travel.", "TV-14", "僕だけがいない街", "Japan"))
        val taste = RecommendationEngine.taste(titles, details) { details[it]?.genres.orEmpty() }
        assertEquals(1.0, taste.kinds[com.auroratv.playback.ContentKind.Anime])
        val candidates = listOf(
            show("Live drama", listOf("Drama", "Mystery"), "Detectives.", "TV-14", score = 9.0),
            show("Anime drama", listOf("Animation", "Drama", "Mystery"), "Detectives.", "TV-14", "探偵", "Japan", score = 7.0),
        )
        assertEquals("Anime drama", RecommendationEngine.rankForTaste(candidates, taste, 5).first().id, "an anime watcher's top pick is anime")
    }
}
