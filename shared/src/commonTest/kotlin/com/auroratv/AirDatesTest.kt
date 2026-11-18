package com.auroratv

import com.auroratv.data.AirDateService
import com.auroratv.data.AirDates
import com.auroratv.data.Upcoming
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.ratings.RatingsService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Upcoming air dates: only real, future dates; calendar dates never shifted by the time zone. */
class AirDatesTest {
    private val hour = 3_600_000L
    private val utc: (Long) -> Long = { 0L }
    private val newYork: (Long) -> Long = { -5 * hour }
    private val tokyo: (Long) -> Long = { 9 * hour }
    /** 17 November 2026, 12:00 UTC. */
    private val now = AirDates.daysFromCivil(2026, 11, 17) * 86_400_000L + 12 * hour

    private fun ep(id: String, s: Int, e: Int, date: String?, name: String? = "Chapter $e", where: String = "Virtual") =
        BaseItemDto(id = id, type = "Episode", name = name, parentIndexNumber = s, indexNumber = e, premiereDate = date, locationType = where, seriesId = "show")

    @Test
    fun calendarDatesKeepTheirDayInEveryTimeZone() {
        // Jellyfin stores a provider's air date as midnight UTC: still the 24th in New York and Tokyo.
        for (zone in listOf(utc, newYork, tokyo)) {
            val d = assertNotNull(Upcoming.airDate("2026-11-24T00:00:00.0000000Z", "The Return", now, zone))
            assertEquals("24 November 2026", d.text)
            assertFalse(d.tentative)
        }
    }

    @Test
    fun exactTimesAreShownInTheLocalTimeZone() {
        // 02:00 UTC on the 25th is the evening of the 24th in New York, the 25th in Tokyo.
        assertEquals("24 November 2026", Upcoming.airDate("2026-11-25T02:00:00.000Z", "Pilot", now, newYork)?.text)
        assertEquals("25 November 2026", Upcoming.airDate("2026-11-25T02:00:00.000Z", "Pilot", now, tokyo)?.text)
        assertEquals("25 November 2026", Upcoming.airDate("2026-11-25T11:00:00+09:00", "Pilot", now, tokyo)?.text)
    }

    @Test
    fun nothingThatHasAiredOrIsUndated() {
        assertNull(Upcoming.airDate("2026-11-10T00:00:00Z", "Old", now, utc), "aired last week")
        assertNull(Upcoming.airDate("2026-11-17T00:00:00Z", "Today", now, utc), "today, time unknown: may have aired")
        assertNull(Upcoming.airDate("2026-11-17T11:00:00Z", "This morning", now, utc), "aired an hour ago")
        assertNotNull(Upcoming.airDate("2026-11-17T20:00:00Z", "Tonight", now, utc), "airs tonight")
        assertNull(Upcoming.airDate(null, "No date", now, utc))
        assertNull(Upcoming.airDate("soon", "Garbage", now, utc))
    }

    @Test
    fun placeholdersAreTentative() {
        val yearOnly = assertNotNull(Upcoming.airDate("2027-01-01T00:00:00Z", "Episode 1", now, utc))
        assertTrue(yearOnly.tentative); assertTrue(yearOnly.yearOnly); assertEquals("2027", yearOnly.text)
        assertTrue(assertNotNull(Upcoming.airDate("2026-12-03T00:00:00Z", "TBA", now, utc)).tentative)
        assertFalse(assertNotNull(Upcoming.airDate("2026-12-03T00:00:00Z", "The Long Night", now, utc)).tentative)
    }

    @Test
    fun serverListKeepsOnlyUnairedEpisodesItDoesNotHave() {
        val list = Upcoming.fromServer(listOf(
            ep("a", 2, 7, "2026-11-16T00:00:00Z"),                    // aired
            ep("b", 2, 8, "2026-11-24T00:00:00Z"),                    // next
            ep("c", 2, 9, "2026-12-01T00:00:00Z"),
            ep("d", 2, 10, "2026-11-20T00:00:00Z", where = "FileSystem"), // already in the library: playable
            ep("e", 3, 1, "2027-03-05T00:00:00Z"),
        ), now, utc)
        assertEquals(listOf("b", "c", "e"), list.map { it.item?.id })
        assertEquals("b", Upcoming.next(list)?.item?.id)
        // Season 3 hasn't started and its first episode is listed: it premieres then. Season 2 has.
        assertEquals(mapOf(3 to "5 March 2027"), Upcoming.seasonPremieres(list, setOf(1, 2)).mapValues { it.value.text })
        // Without episode 1 the start of a season isn't known.
        assertTrue(Upcoming.seasonPremieres(list.filter { it.episodeNumber != 1 }, setOf(1, 2)).isEmpty())
    }

    @Test
    fun traktTimeRefinesAServerDate() {
        val server = Upcoming.fromServer(listOf(ep("b", 2, 8, "2026-11-25T00:00:00Z")), now, newYork)
        val merged = AirDateService.merge(server, RatingsService.NextEpisode(2, 8, "Chapter 8", "2026-11-25T02:00:00.000Z"), now, newYork)
        assertEquals(1, merged.size)
        assertEquals("24 November 2026", merged.single().date.text, "the exact time puts it on the 24th in New York")
        // Not on the server at all: added.
        val added = AirDateService.merge(emptyList(), RatingsService.NextEpisode(3, 1, "Premiere", "2027-03-05T01:00:00.000Z"), now, utc)
        assertEquals(3, added.single().seasonNumber)
        assertNull(RatingsService.parseTraktNextEpisode("not json"))
        assertEquals(8, RatingsService.parseTraktNextEpisode("""{"season":2,"number":8,"title":"x","first_aired":"2026-11-25T02:00:00.000Z","ids":{}}""")?.number)
    }

    @Test
    fun civilDateRoundTrip() {
        for (d in listOf(Triple(1970, 1, 1), Triple(2000, 2, 29), Triple(2026, 11, 24), Triple(2100, 12, 31))) {
            assertEquals(d, AirDates.civilFromDays(AirDates.daysFromCivil(d.first, d.second, d.third)))
        }
        assertEquals(0L, AirDates.daysFromCivil(1970, 1, 1))
    }
}
