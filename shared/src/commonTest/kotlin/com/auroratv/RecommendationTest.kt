package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.UserItemData
import com.auroratv.recommend.RecommendationEngine
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
}
