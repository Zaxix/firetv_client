package com.auroratv.profiles

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.platform.FileStore
import com.auroratv.playback.SkipRange
import com.auroratv.ratings.ExternalRatings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * File names for a profile's own data: `<base>-<profile>.json`. Profiles share everything else
 * (the server's catalogue, image cache, saved servers); only these small files are per person.
 */
object ProfileFiles {
    /** Every per-profile file kind, so removing a profile can clean them all up. */
    val ALL = listOf("settings", "track-memory", "ratings", "skip-markers", "search-history", "recommendations", "watch", "split-watch")

    fun name(base: String, profile: String) = "$base-$profile.json"

    /**
     * Before profiles existed some data lived in `<base>.json`; the first profile to open takes it
     * over (moved, not copied, so it never ends up in two profiles).
     */
    fun claimLegacy(files: FileStore, base: String, profile: String) {
        val legacy = runCatching { files.read("$base.json") }.getOrNull() ?: return
        if (files.read(name(base, profile)) == null) files.write(name(base, profile), legacy)
        files.delete("$base.json")
        Log.i("Profiles", "Moved $base.json into profile $profile")
    }

    fun deleteAll(files: FileStore, profile: String) = ALL.forEach { runCatching { files.delete(name(it, profile)) } }
}

/**
 * Ratings shown on detail pages, kept between sessions so pages fill in instantly and the rating
 * services aren't asked again for a week. Per profile; each entry is just the title key and its
 * scores packed into one short string (`"88,91,7.9,523000,74,3.9,20410"`), with the day it was
 * fetched for expiry. Capped at [MAX] titles, least recently fetched dropped first.
 */
class RatingsStore(private val files: FileStore, private val today: () -> Int) {
    private var profile: String? = null
    private var map: MutableMap<String, String> = mutableMapOf()
    private val serializer = MapSerializer(String.serializer(), String.serializer())

    fun switchProfile(key: String) {
        profile = key
        map = runCatching { files.read(ProfileFiles.name(BASE, key))?.let { AuroraJson.decodeFromString(serializer, it).toMutableMap() } }.getOrNull() ?: mutableMapOf()
        // Drop expired entries on load, so the file only ever holds recent titles.
        val before = map.size
        map.entries.removeAll { (_, v) -> today() - (v.substringAfterLast(',').toIntOrNull() ?: 0) > TTL_DAYS }
        if (map.size != before) persist()
    }

    fun get(titleKey: String): ExternalRatings? {
        val packed = map[titleKey] ?: return null
        val p = packed.split(',')
        if (p.size != 7) return null
        val day = p[6].toIntOrNull() ?: return null
        if (today() - day > TTL_DAYS) return null
        return ExternalRatings(
            tomatometer = p[0].toIntOrNull(), popcornmeter = p[1].toIntOrNull(), imdb = p[2].toDoubleOrNull(),
            imdbVotes = p[3].toIntOrNull(), metacritic = p[4].toIntOrNull(), letterboxd = p[5].toDoubleOrNull(),
            source = SOURCE,
        )
    }

    fun put(titleKey: String, r: ExternalRatings) {
        if (profile == null) return
        fun n(v: Any?) = v?.toString().orEmpty()
        map.remove(titleKey)
        map[titleKey] = listOf(n(r.tomatometer), n(r.popcornmeter), n(r.imdb), n(r.imdbVotes), n(r.metacritic), n(r.letterboxd), today().toString()).joinToString(",")
        while (map.size > MAX) map.remove(map.keys.first())
        persist()
    }

    private fun persist() {
        val key = profile ?: return
        runCatching { files.write(ProfileFiles.name(BASE, key), AuroraJson.encodeToString(serializer, map)) }
    }

    companion object {
        const val BASE = "ratings"
        const val TTL_DAYS = 7
        const val MAX = 1500
        const val SOURCE = "MDBList"
    }
}

/**
 * Skip markers this device worked out itself (matching chapters across a season, AniSkip, matching
 * audio, credits on screen) for episodes being watched, so they're there at once when playback
 * resumes, and which kinds were already looked for, so a search that found nothing isn't repeated
 * (no repeated analysis of the same episode). Temporary by design:
 *
 * - only *detected* markers are kept; server segments and chapter names come with the episode
 *   itself and are never copied here;
 * - an episode's entry is deleted as soon as it's finished;
 * - anything untouched for [ABANDON_DAYS] (started, never finished) is pruned when the profile opens.
 */
class SkipMarkerStore(private val files: FileStore, private val today: () -> Int) {
    /** [r] ranges, [s] source, [d] day stored, [t] kinds already looked for ("i", "c", "r"). */
    @Serializable
    private data class Entry(val r: String, val s: String, val d: Int, val t: String = "")

    private var profile: String? = null
    private var map: MutableMap<String, Entry> = mutableMapOf()
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())

    fun switchProfile(key: String) {
        profile = key
        map = runCatching { files.read(ProfileFiles.name(BASE, key))?.let { AuroraJson.decodeFromString(serializer, it).toMutableMap() } }.getOrNull() ?: mutableMapOf()
        val before = map.size
        map.entries.removeAll { today() - it.value.d > ABANDON_DAYS }
        if (map.size != before) persist()
    }

    /** Remembered markers for an episode in progress (and where they came from), or null. */
    fun get(episodeId: String): Pair<List<SkipRange>, String>? {
        val e = map[episodeId] ?: return null
        val ranges = e.r.split(';').filter { it.isNotEmpty() }.mapNotNull { part ->
            val kind = when (part.firstOrNull()) { 'i' -> SkipRange.Kind.Intro; 'r' -> SkipRange.Kind.Recap; 'c' -> SkipRange.Kind.Credits; else -> null }
            // A trailing "?" marks a weak match (SkipRange.confident = false).
            val weak = part.endsWith('?')
            val (a, b) = part.drop(1).removeSuffix("?").split('-').takeIf { it.size == 2 } ?: return@mapNotNull null
            val start = a.toLongOrNull(); val end = b.toLongOrNull()
            if (kind == null || start == null || end == null) null else SkipRange(kind, start, end, confident = !weak)
        }
        return if (ranges.isEmpty()) null else ranges to e.s
    }

    /** Kinds already looked for in this episode (found or not). */
    fun tried(episodeId: String): Set<SkipRange.Kind> {
        val e = map[episodeId] ?: return emptySet()
        return e.t.mapNotNull { letterKind(it) }.toSet() + (get(episodeId)?.first?.map { it.kind }.orEmpty())
    }

    /** Stores [ranges] (may be empty when nothing was found) and that [tried] kinds were looked for. */
    fun put(episodeId: String, ranges: List<SkipRange>, source: String, tried: Set<SkipRange.Kind> = ranges.map { it.kind }.toSet()) {
        if (profile == null || (ranges.isEmpty() && tried.isEmpty())) return
        val packed = ranges.joinToString(";") { r -> "${kindLetter(r.kind)}${r.startMs}-${r.endMs}${if (r.confident) "" else "?"}" }
        map[episodeId] = Entry(packed, source.take(40), today(), tried.map(::kindLetter).sorted().joinToString(""))
        while (map.size > MAX) map.remove(map.keys.first())
        persist()
    }

    /** The episode was finished: its markers aren't needed any more. */
    fun finished(episodeId: String) {
        if (map.remove(episodeId) != null) persist()
    }

    val size: Int get() = map.size

    private fun kindLetter(k: SkipRange.Kind) = when (k) { SkipRange.Kind.Intro -> 'i'; SkipRange.Kind.Recap -> 'r'; SkipRange.Kind.Credits -> 'c' }
    private fun letterKind(c: Char) = when (c) { 'i' -> SkipRange.Kind.Intro; 'r' -> SkipRange.Kind.Recap; 'c' -> SkipRange.Kind.Credits; else -> null }

    private fun persist() {
        val key = profile ?: return
        runCatching {
            if (map.isEmpty()) files.delete(ProfileFiles.name(BASE, key))
            else files.write(ProfileFiles.name(BASE, key), AuroraJson.encodeToString(serializer, map))
        }
    }

    companion object {
        const val BASE = "skip-markers"
        const val ABANDON_DAYS = 30
        const val MAX = 200
    }
}

/** Recent searches for one profile (only queries that led somewhere: a result was opened). */
class SearchHistory(private val files: FileStore) {
    private var profile: String? = null
    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()
    private val serializer = ListSerializer(String.serializer())

    fun switchProfile(key: String) {
        profile = key
        _recent.value = runCatching { files.read(ProfileFiles.name(BASE, key))?.let { AuroraJson.decodeFromString(serializer, it) } }.getOrNull().orEmpty()
    }

    fun add(query: String) {
        val q = query.trim().replace(Regex("\\s+"), " ")
        if (q.length < 2) return
        _recent.value = (listOf(q) + _recent.value.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        persist()
    }

    fun clear() { _recent.value = emptyList(); persist() }

    private fun persist() {
        val key = profile ?: return
        runCatching { files.write(ProfileFiles.name(BASE, key), AuroraJson.encodeToString(serializer, _recent.value)) }
    }

    companion object {
        const val BASE = "search-history"
        const val MAX = 12
    }
}

/** Days since 1970 (for the stores' expiry dates). */
fun epochDay(nowMs: Long): Int = (nowMs / 86_400_000L).toInt()
