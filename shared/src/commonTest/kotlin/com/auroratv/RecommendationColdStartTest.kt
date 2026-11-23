package com.auroratv

import com.auroratv.recommend.RecommendationService
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "Recommended For You" for a profile that has watched little or nothing: suggestions from its
 * favourites, else the library's best-rated titles across genres; and a failed request is never
 * saved as the answer.
 */
class RecommendationColdStartTest {
    private class Title(val id: String, val name: String, val genres: List<String>, val rating: Double, val age: String = "PG-13")

    private val library = listOf(
        Title("sci1", "Star Harbor", listOf("Science Fiction"), 8.1),
        Title("sci2", "Orbit Line", listOf("Science Fiction", "Drama"), 7.5),
        Title("sci3", "Deep Signal", listOf("Science Fiction", "Thriller"), 7.0),
        Title("com1", "Paper Crowns", listOf("Comedy"), 8.5),
        Title("com2", "Spare Keys", listOf("Comedy"), 6.0),
        Title("dra1", "Tidewater", listOf("Drama"), 8.8),
        Title("hor1", "Low Hollow", listOf("Horror"), 7.9, "R"),
        Title("kid1", "Puddle Jumpers", listOf("Family"), 9.0, "TV-Y"),
    )

    private fun dto(t: Title, favorite: Boolean = false) =
        """{"Id":"${t.id}","Name":"${t.name}","Type":"Movie","Genres":[${t.genres.joinToString(",") { "\"$it\"" }}],"CommunityRating":${t.rating},"OfficialRating":"${t.age}","UserData":{"Played":false,"PlayCount":0,"IsFavorite":$favorite}}"""

    private fun list(items: List<String>) = """{"Items":[${items.joinToString(",")}]}"""

    /** A server with [library]; [favorites] are this profile's; [failHighlights]: the all-genres query fails. */
    private fun server(favorites: Set<String> = emptySet(), failHighlights: Boolean = false) = FakeServer { req ->
        val p = req.url.parameters
        when {
            req.url.encodedPath.endsWith("/Similar") -> json(list(emptyList()))
            p["filters"] == "IsPlayed" -> json(list(emptyList())) // nothing watched yet
            p["isFavorite"] == "true" -> json(list(library.filter { it.id in favorites }.map { dto(it, true) }))
            p["ids"] != null -> json(list(library.filter { it.id in p["ids"]!!.split(',') }.map { dto(it, it.id in favorites) }))
            p["genres"] != null -> {
                val wanted = p["genres"]!!.split('|').toSet()
                json(list(library.filter { t -> t.genres.any { it in wanted } }.sortedByDescending { it.rating }.map { dto(it, it.id in favorites) }))
            }
            p["filters"] == "IsUnplayed" -> if (failHighlights) json("{}", HttpStatusCode.ServiceUnavailable)
                else json(list(library.sortedByDescending { it.rating }.map { dto(it, it.id in favorites) }))
            else -> json(list(emptyList()))
        }
    }

    @Test
    fun aNewProfileGetsTheLibrarysBestAcrossGenres() = runTest {
        val fake = server()
        val a = api(fake)
        val files = MemoryFileStore()
        val svc = RecommendationService(this, files)
        val plan = svc.plan(a, emptyList())
        assertTrue(plan.titles.isEmpty() && plan.seeds.isEmpty())
        val rows = assertNotNull(svc.rows(a, "new", plan).await())
        val forYou = assertNotNull(rows.firstOrNull { it.key == "foryou" }, "a new profile still gets Recommended For You")
        val names = forYou.items.map { it.name }
        println("new profile: $names")
        // Best-rated first, taking turns across genres (not three comedies in a row).
        assertEquals(listOf("Puddle Jumpers", "Tidewater", "Paper Crowns", "Star Harbor", "Low Hollow"), names.take(5))
        assertEquals(library.size, names.size)
        // Worked out completely: saved, so the next launch asks the server nothing.
        assertTrue(files.data.keys.any { "recommendations" in it })
    }

    @Test
    fun aNewProfilesFavouritesSteerItsSuggestions() = runTest {
        val fake = server(favorites = setOf("sci1"))
        val a = api(fake)
        val svc = RecommendationService(this, MemoryFileStore())
        val plan = svc.plan(a, emptyList())
        assertEquals(listOf("sci1"), plan.favorites.map { it.id })
        val forYou = assertNotNull(svc.rows(a, "fan", plan).await()?.firstOrNull { it.key == "foryou" })
        val ids = forYou.items.map { it.id }
        println("sci-fi fan: $ids")
        assertEquals(setOf("sci2", "sci3"), ids.take(2).toSet(), "titles in the favourite's genre come first")
        assertTrue("sci1" !in ids, "a favourite isn't suggested back")
        assertTrue("kid1" !in ids, "no children's titles for a profile whose taste has none")
    }

    @Test
    fun aFailedRequestIsNotSavedAsTheAnswer() = runTest {
        val fake = server(failHighlights = true)
        val a = api(fake)
        val files = MemoryFileStore()
        val svc = RecommendationService(this, files)
        val plan = svc.plan(a, emptyList())
        val rows = assertNotNull(svc.rows(a, "new", plan).await(), "what did load is still returned")
        assertNull(rows.firstOrNull { it.key == "foryou" }, "nothing to suggest without the library's list")
        assertTrue(files.data.keys.none { "recommendations" in it }, "not saved: the next launch works it out again instead of showing nothing for good")
    }
}
