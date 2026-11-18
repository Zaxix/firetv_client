package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.platform.FileStore
import com.auroratv.playback.SkipRange
import com.auroratv.profiles.ProfileFiles
import com.auroratv.profiles.RatingsStore
import com.auroratv.profiles.SearchHistory
import com.auroratv.profiles.SkipMarkerStore
import com.auroratv.ratings.ExternalRatings
import com.auroratv.search.SearchIndex
import com.auroratv.settings.SettingsRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class MapFiles : FileStore {
    val map = mutableMapOf<String, String>()
    override fun read(name: String) = map[name]
    override fun write(name: String, content: String) { map[name] = content }
    override fun delete(name: String) { map.remove(name) }
}

class SearchIndexTest {
    private fun movie(name: String, year: Int? = null, genres: List<String> = emptyList(), type: String = "Movie") =
        BaseItemDto(id = name.lowercase().replace(' ', '-'), name = name, type = type, productionYear = year, genres = genres)

    private val index = SearchIndex(listOf(
        movie("Interstellar", 2014, listOf("Science Fiction", "Drama")),
        movie("Breaking Bad", 2008, listOf("Drama", "Crime"), type = "Series"),
        movie("The Lord of the Rings: The Fellowship of the Ring", 2001, listOf("Fantasy")),
        movie("Star Wars", 1977, listOf("Science Fiction")),
        movie("Star Trek", 2009, listOf("Science Fiction")),
        movie("Alien", 1979, listOf("Horror")),
        movie("Aliens", 1986, listOf("Action")),
        movie("Alien: Romulus", 2024, listOf("Horror")),
        movie("Amélie", 2001, listOf("Comedy", "Romance")),
        movie("Spirited Away", 2001, listOf("Animation")),
    ))

    private fun top(q: String) = index.search(q).firstOrNull()?.item?.name

    @Test fun exactBeatsLongerTitles() = assertEquals("Alien", top("alien"))
    @Test fun partialTitle() = assertEquals("Interstellar", top("inters"))
    @Test fun typosStillMatch() {
        assertEquals("Interstellar", top("intersteller"))
        assertEquals("Breaking Bad", top("brekaing bad"))
        assertEquals("Spirited Away", top("spirted away"))
    }
    @Test fun wordsInAnyOrderAndMissingSmallWords() = assertEquals("The Lord of the Rings: The Fellowship of the Ring", top("rings lord"))
    @Test fun accentsAndPunctuationIgnored() = assertEquals("Amélie", top("amelie"))
    @Test fun yearNarrowsResults() {
        assertEquals("Alien: Romulus", top("alien 2024"))
        assertTrue(index.search("star 1977").none { it.item.name == "Star Trek" })
    }
    @Test fun genreFindsTitlesBelowTitleMatches() {
        val hits = index.search("horror")
        assertEquals(setOf("Alien", "Alien: Romulus"), hits.map { it.item.name }.toSet())
        assertTrue(hits.all { it.kind == SearchIndex.Kind.Genre })
    }
    @Test fun noFalseFuzzyMatchesOnShortWords() = assertTrue(index.search("xyz").isEmpty())
    @Test fun suggestionsCompleteTitles() = assertEquals(listOf("Star Wars", "Star Trek").sorted(), index.suggest("star").sorted())
    @Test fun distanceCountsSwapsAsOne() {
        assertEquals(1, SearchIndex.distance("brekaing", "breaking", 2))
        assertEquals(3, SearchIndex.distance("abc", "xyzabc", 2))
    }
}

class ProfileStoresTest {
    @Test
    fun ratingsPersistPerProfileAndExpire() {
        val files = MapFiles()
        var day = 20_000
        val a = RatingsStore(files) { day }
        a.switchProfile("alice")
        a.put("m603", ExternalRatings(tomatometer = 88, popcornmeter = 91, imdb = 7.9, imdbVotes = 523000, metacritic = 74, letterboxd = 3.9, source = "MDBList"))
        // Same title, other profile: nothing.
        val b = RatingsStore(files) { day }
        b.switchProfile("bob")
        assertNull(b.get("m603"))
        // Survives a restart for the same profile.
        val again = RatingsStore(files) { day }
        again.switchProfile("alice")
        assertEquals(88, again.get("m603")?.tomatometer)
        assertEquals(7.9, again.get("m603")?.imdb)
        // A week later it's fetched afresh and the entry is dropped on load.
        day += 8
        val later = RatingsStore(files) { day }
        later.switchProfile("alice")
        assertNull(later.get("m603"))
        assertTrue(files.map[ProfileFiles.name("ratings", "alice")]!!.length < 10)
    }

    @Test
    fun skipMarkersLastUntilTheEpisodeIsFinished() {
        val files = MapFiles()
        var day = 20_000
        val store = SkipMarkerStore(files) { day }
        store.switchProfile("alice")
        // A weak match stays weak after a restart (never auto-skipped).
        val ranges = listOf(SkipRange(SkipRange.Kind.Intro, 61_000, 150_000), SkipRange(SkipRange.Kind.Credits, 1_300_000, 1_420_000, confident = false))
        store.put("ep1", ranges, "AniSkip")
        // Stopped halfway, app restarted: still there.
        val resumed = SkipMarkerStore(files) { day }
        resumed.switchProfile("alice")
        assertEquals(ranges to "AniSkip", resumed.get("ep1"))
        // Finished: gone, and the file with it.
        resumed.finished("ep1")
        assertNull(resumed.get("ep1"))
        assertNull(files.map[ProfileFiles.name("skip-markers", "alice")])
        // Abandoned episodes are pruned after a month.
        resumed.put("ep2", ranges, "matching chapters")
        day += 31
        val pruned = SkipMarkerStore(files) { day }
        pruned.switchProfile("alice")
        assertEquals(0, pruned.size)
    }

    @Test
    fun searchHistoryAndSettingsArePerProfile() {
        val files = MapFiles()
        val h = SearchHistory(files)
        h.switchProfile("alice"); h.add("interstellar"); h.add("Interstellar"); h.add("alien")
        assertEquals(listOf("alien", "Interstellar"), h.recent.value)
        h.switchProfile("bob")
        assertTrue(h.recent.value.isEmpty())

        // Settings from before profiles carry over to the first profile; each profile then keeps its own.
        files.write("settings.json", """{"subtitleScale":1.5}""")
        val s = SettingsRepository(files)
        s.switchProfile("alice")
        assertEquals(1.5f, s.current.subtitleScale)
        s.update { it.copy(subtitleScale = 1.75f) }
        s.switchProfile("bob")
        s.update { it.copy(subtitleScale = 1.0f) }
        s.switchProfile("alice")
        assertEquals(1.75f, s.current.subtitleScale)
        assertNotNull(files.map[ProfileFiles.name("settings", "bob")])
    }
}
