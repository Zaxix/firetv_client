package com.auroratv.data

import com.auroratv.jellyfin.model.BaseItemDto

/** The device's offset from UTC at [epochMs], in ms (daylight saving included). */
expect fun localUtcOffsetMs(epochMs: Long): Long

/**
 * When upcoming episodes air, from dates the server's metadata (or Trakt) already has; nothing is
 * guessed. Two kinds of date come in:
 *
 * - **A calendar date** (Jellyfin stores provider air dates as midnight UTC): shown as that date,
 *   never shifted by the time zone (midnight UTC is the evening before in the Americas). It counts as
 *   upcoming only from the day after today, because the time of day isn't known.
 * - **An exact time** (Trakt's `first_aired`, or a server time that isn't midnight): converted to the
 *   device's time zone, and upcoming until that moment.
 *
 * A date that's only a placeholder (1 January, "TBA" episodes) is tentative: shown as "Expected".
 */
object AirDates {
    private const val DAY_MS = 86_400_000L

    /** [epochDay]: the calendar date as given (for a time, its UTC date); [instantMs]: the moment. */
    data class Parsed(val epochDay: Long, val hasTime: Boolean, val instantMs: Long)

    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?)?\s*(Z|[+-]\d{2}:?\d{2})?$""")

    fun parse(iso: String?): Parsed? {
        val m = ISO.matchEntire(iso?.trim() ?: return null) ?: return null
        val g = m.groupValues
        val y = g[1].toInt(); val mo = g[2].toInt(); val d = g[3].toInt()
        if (mo !in 1..12 || d !in 1..31 || y < 1900) return null
        val hh = g[4].toIntOrNull() ?: 0; val mm = g[5].toIntOrNull() ?: 0; val ss = g[6].toIntOrNull() ?: 0
        val zone = g[7]
        val offsetMs = when {
            zone.isEmpty() || zone == "Z" -> 0L
            else -> {
                val digits = zone.drop(1).replace(":", "")
                val minutes = digits.take(2).toInt() * 60 + digits.drop(2).toInt()
                (if (zone[0] == '-') -1 else 1) * minutes * 60_000L
            }
        }
        val day = daysFromCivil(y, mo, d)
        val instant = day * DAY_MS + (hh * 3600 + mm * 60 + ss) * 1000L - offsetMs
        // Midnight exactly is how a date without a time is stored.
        val hasTime = hh != 0 || mm != 0 || ss != 0
        return Parsed(day, hasTime, instant)
    }

    /** The date the viewer sees: a calendar date as is; an exact time in the device's time zone. */
    fun localDay(p: Parsed, offset: (Long) -> Long = ::localUtcOffsetMs): Long =
        if (p.hasTime) (p.instantMs + offset(p.instantMs)).floorDiv(DAY_MS) else p.epochDay

    /** True while it hasn't aired: before its moment, or (date only) from tomorrow on. */
    fun isUpcoming(p: Parsed, nowMs: Long, offset: (Long) -> Long = ::localUtcOffsetMs): Boolean =
        if (p.hasTime) p.instantMs > nowMs else p.epochDay > (nowMs + offset(nowMs)).floorDiv(DAY_MS)

    /** "24 November 2026". */
    fun format(epochDay: Long): String {
        val (y, m, d) = civilFromDays(epochDay)
        return "$d ${MONTHS[m - 1]} $y"
    }

    fun year(epochDay: Long): Int = civilFromDays(epochDay).first

    private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
    fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (y).floorDiv(400L)
        val yoe = y - era * 400
        val mp = (month + 9) % 12
        val doy = (153 * mp + 2) / 5 + day - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097 + doe - 719_468
    }

    fun civilFromDays(days: Long): Triple<Int, Int, Int> {
        val z = days + 719_468
        val era = (z).floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
        val m = (if (mp < 10) mp + 3 else mp - 9).toInt()
        val y = (yoe + era * 400 + if (m <= 2) 1 else 0).toInt()
        return Triple(y, m, d)
    }
}

/** A known air date as the viewer sees it. */
data class AirDate(
    /** Local calendar day (days since 1970-01-01). */
    val day: Long,
    /** Not a firm date: a placeholder (1 January) or an episode still listed as "TBA". */
    val tentative: Boolean,
    /** Only the year is really known (a 1 January placeholder): shown as the year alone. */
    val yearOnly: Boolean = false,
    /** The exact moment, when known (else it's a calendar date and [day] is all there is). */
    val instantMs: Long? = null,
) {
    /** Still ahead at [nowMs] (a kept date is checked again before it's shown). */
    fun isUpcoming(nowMs: Long, offset: (Long) -> Long = ::localUtcOffsetMs): Boolean =
        instantMs?.let { it > nowMs } ?: (day > (nowMs + offset(nowMs)).floorDiv(86_400_000L))

    /** "24 November 2026", or "2027" when only the year is known. */
    val text: String get() = if (yearOnly) AirDates.year(day).toString() else AirDates.format(day)
}

/** An episode that hasn't aired yet, with its air date. [item] is the server's (virtual) episode, if any. */
data class UpcomingEpisode(
    val seasonNumber: Int?,
    val episodeNumber: Int?,
    val name: String?,
    val date: AirDate,
    val seasonId: String? = null,
    val item: BaseItemDto? = null,
)

object Upcoming {
    private val PLACEHOLDER_NAME = Regex("""^\s*(tba|tbd|to be announced|to be determined|episode\s*#?\s*\d+)\s*$""", RegexOption.IGNORE_CASE)

    /** The air date of an episode that hasn't aired, or null (aired, no date, or not an episode). */
    fun airDate(premiere: String?, name: String?, nowMs: Long, offset: (Long) -> Long = ::localUtcOffsetMs): AirDate? {
        val p = AirDates.parse(premiere) ?: return null
        if (!AirDates.isUpcoming(p, nowMs, offset)) return null
        val day = AirDates.localDay(p, offset)
        val (_, m, d) = AirDates.civilFromDays(day)
        val yearPlaceholder = !p.hasTime && m == 1 && d == 1
        val tentative = yearPlaceholder || name.isNullOrBlank() || PLACEHOLDER_NAME.matches(name)
        return AirDate(day, tentative, yearOnly = yearPlaceholder, instantMs = p.instantMs.takeIf { p.hasTime })
    }

    /**
     * Upcoming episodes from the server's own listing: only episodes it doesn't have yet (virtual
     * items) whose air date is still ahead. One already in the library is playable, so it's not
     * "upcoming" whatever its date says.
     */
    fun fromServer(items: List<BaseItemDto>, nowMs: Long, offset: (Long) -> Long = ::localUtcOffsetMs): List<UpcomingEpisode> =
        items.asSequence()
            .filter { it.isEpisode && it.locationType == "Virtual" }
            .mapNotNull { ep ->
                airDate(ep.premiereDate, ep.name, nowMs, offset)?.let { UpcomingEpisode(ep.parentIndexNumber, ep.indexNumber, ep.name, it, ep.seasonId, ep) }
            }
            .sortedWith(compareBy<UpcomingEpisode>({ it.date.day }, { it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }))
            .toList()

    /** The next episode to air (firm dates before tentative ones on the same day). */
    fun next(list: List<UpcomingEpisode>): UpcomingEpisode? =
        list.minWithOrNull(compareBy<UpcomingEpisode>({ it.date.day }, { it.date.tentative }, { it.seasonNumber ?: Int.MAX_VALUE }, { it.episodeNumber ?: Int.MAX_VALUE }))

    /**
     * When a season that hasn't started yet premieres: the air date of its first episode, only when
     * that episode is among the upcoming ones (otherwise the season may already have started).
     * [airedSeasons]: season numbers with episodes already in the library.
     */
    fun seasonPremieres(list: List<UpcomingEpisode>, airedSeasons: Set<Int>): Map<Int, AirDate> =
        list.filter { it.seasonNumber != null && it.seasonNumber > 0 && it.seasonNumber !in airedSeasons }
            .groupBy { it.seasonNumber!! }
            .mapNotNull { (season, eps) -> eps.firstOrNull { it.episodeNumber == 1 }?.let { season to it.date } }
            .toMap()
}
