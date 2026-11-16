package com.auroratv.search

import com.auroratv.jellyfin.model.BaseItemDto
import kotlin.math.max
import kotlin.math.min

/**
 * On-device search over the library's movie, series and collection titles. The server's own search
 * only finds exact substrings; this adds what people expect from a streaming app:
 *
 * - **Instant:** scoring a few thousand titles takes milliseconds, so results follow every keypress
 *   with no request at all.
 * - **Typos:** a word within one or two edits (depending on its length) still matches
 *   ("intersteller" → Interstellar, "brekaing bad" → Breaking Bad).
 * - **Partial and multi-word:** each word may be the start of a title word ("star wa"), words can
 *   come in any order, and a missing small word ("lord rings") is fine.
 * - **Years and genres:** a year ("alien 1979") narrows by release year; a genre word ("comedy")
 *   matches titles in that genre, ranked below title matches.
 * - **Ranking:** exact title > title starting with the query > all words matching as prefixes >
 *   typo matches > genre-only matches; ties go to the closer length and newer year.
 */
class SearchIndex(items: List<BaseItemDto>) {
    private class Entry(
        val item: BaseItemDto,
        val title: String,
        val words: List<String>,
        val altWords: List<String>,
        val genres: Set<String>,
        val year: Int?,
    )

    private val entries: List<Entry> = items.mapNotNull { item ->
        val name = item.name ?: return@mapNotNull null
        val title = normalize(name)
        Entry(
            item = item,
            title = title,
            words = words(title),
            altWords = item.originalTitle?.let { o -> words(normalize(o)).filterNot { it in words(title) } }.orEmpty(),
            genres = item.genres.map { normalize(it) }.toSet(),
            year = item.productionYear,
        )
    }

    /** Every genre in the library, most common first (for "Browse by genre" suggestions). */
    val genres: List<String> = items.flatMap { it.genres }.groupingBy { it }.eachCount()
        .entries.sortedByDescending { it.value }.map { it.key }

    val size: Int get() = entries.size

    data class Hit(val item: BaseItemDto, val score: Int, val kind: Kind)

    enum class Kind { Exact, Prefix, Words, Fuzzy, Genre }

    /** Ranked matches for [query], best first. */
    fun search(query: String, limit: Int = 60): List<Hit> {
        val q = parse(query) ?: return emptyList()
        val hits = ArrayList<Hit>()
        for (e in entries) {
            score(e, q)?.let { hits += it }
        }
        return hits.sortedWith(compareByDescending<Hit> { it.score }.thenByDescending { it.item.productionYear ?: 0 }).take(limit)
    }

    /** Title completions for the search box: best title matches, without genre-only ones. */
    fun suggest(query: String, limit: Int = 6): List<String> =
        search(query, limit * 3).filter { it.kind != Kind.Genre }.mapNotNull { it.item.name }.distinct().take(limit)

    // ---- scoring ------------------------------------------------------------------------------

    private class Query(val text: String, val words: List<String>, val year: Int?)

    private fun parse(raw: String): Query? {
        val norm = normalize(raw)
        val all = words(norm)
        // A trailing or standalone 4-digit year narrows by release year instead of matching the title.
        val year = all.lastOrNull()?.toIntOrNull()?.takeIf { it in 1900..2100 && all.size > 1 }
        val w = if (year != null) all.dropLast(1) else all
        if (w.isEmpty() || w.joinToString("").length < 2) return null
        return Query(w.joinToString(" "), w, year)
    }

    private fun score(e: Entry, q: Query): Hit? {
        if (q.year != null && e.year != null && abs(e.year - q.year) > 1) return null
        val yearBonus = if (q.year != null && e.year == q.year) 40 else 0
        val lengthPenalty = min(60, max(0, e.title.length - q.text.length))
        // 1. Exact / starts-with on the whole title.
        if (e.title == q.text) return Hit(e.item, 1000 + yearBonus, Kind.Exact)
        if (e.title.startsWith(q.text)) return Hit(e.item, 900 - lengthPenalty + yearBonus, Kind.Prefix)
        // 2. Every query word matches a title word (exactly, as a prefix, or inside a longer word).
        var exactWords = 0; var prefixWords = 0; var fuzzyWords = 0; var innerWords = 0
        val titleWords = e.words + e.altWords
        for ((i, w) in q.words.withIndex()) {
            val last = i == q.words.lastIndex
            when {
                titleWords.any { it == w } -> exactWords++
                titleWords.any { it.startsWith(w) } -> prefixWords++
                w.length >= 3 && e.title.contains(w) -> innerWords++
                // The word still being typed is matched as a prefix even with a typo ("interst" ~ "interstellar").
                titleWords.any { fuzzyMatch(w, it, allowPrefix = last) } -> fuzzyWords++
                isFiller(w) -> exactWords++ // "the", "of", "and": don't sink a match over a missing small word
                else -> return genreHit(e, q, yearBonus)
            }
        }
        val n = q.words.size
        return when {
            fuzzyWords == 0 && innerWords == 0 -> Hit(e.item, 700 + 20 * exactWords - lengthPenalty / 2 + yearBonus, Kind.Words)
            fuzzyWords == 0 -> Hit(e.item, 560 - 10 * innerWords - lengthPenalty / 2 + yearBonus, Kind.Words)
            fuzzyWords <= max(1, n / 2 + 1) -> Hit(e.item, 420 - 30 * fuzzyWords - lengthPenalty / 2 + yearBonus, Kind.Fuzzy)
            else -> genreHit(e, q, yearBonus)
        }
    }

    private fun genreHit(e: Entry, q: Query, yearBonus: Int): Hit? {
        if (e.genres.isEmpty()) return null
        val all = q.words.all { w -> e.genres.any { g -> g == w || g.startsWith(w) || (w.length >= 5 && fuzzyMatch(w, g, allowPrefix = false)) } }
        return if (all) Hit(e.item, 200 + yearBonus, Kind.Genre) else null
    }

    companion object {
        private val FILLERS = setOf("the", "a", "an", "of", "and", "in", "on", "to", "de", "la", "le")
        private fun isFiller(w: String) = w in FILLERS

        /** Lower case, accents and punctuation removed, "&" → "and", single spaces. */
        fun normalize(s: String): String {
            val sb = StringBuilder(s.length)
            for (ch in s.lowercase()) {
                val c = fold(ch)
                when {
                    c == '&' -> sb.append(" and ")
                    c.isLetterOrDigit() -> sb.append(c)
                    c == '\'' || c == '’' -> Unit // "don't" → "dont"
                    else -> sb.append(' ')
                }
            }
            return sb.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        }

        private fun words(s: String) = s.split(' ').filter { it.isNotEmpty() }

        private fun fold(c: Char): Char = when (c) {
            in "àáâãäåā" -> 'a'; in "çćč" -> 'c'; in "èéêëēė" -> 'e'; in "ìíîïī" -> 'i'
            in "ñń" -> 'n'; in "òóôõöøō" -> 'o'; in "ùúûüū" -> 'u'; in "ýÿ" -> 'y'; in "śš" -> 's'; in "źżž" -> 'z'
            'ß' -> 's'; 'æ' -> 'a'; 'œ' -> 'o'
            else -> c
        }

        /** Edits allowed for a word of this length: none for short words (too many false hits). */
        private fun allowedEdits(len: Int) = when {
            len <= 3 -> 0
            len <= 6 -> 1
            else -> 2
        }

        /**
         * [w] is [target] (or, when [allowPrefix], the start of it) within a few edits — insertions,
         * deletions, substitutions and swapped neighbours (optimal string alignment distance).
         */
        internal fun fuzzyMatch(w: String, target: String, allowPrefix: Boolean): Boolean {
            val k = allowedEdits(w.length)
            if (k == 0) return false
            if (!allowPrefix && abs(w.length - target.length) > k) return false
            val t = if (allowPrefix && target.length > w.length + k) target.substring(0, w.length + k) else target
            return if (allowPrefix) prefixDistance(w, t, k) <= k else distance(w, t, k) <= k
        }

        /** Optimal string alignment distance, giving up (returning k+1) once it must exceed [k]. */
        internal fun distance(a: String, b: String, k: Int): Int {
            if (abs(a.length - b.length) > k) return k + 1
            var prev2 = IntArray(b.length + 1)
            var prev = IntArray(b.length + 1) { it }
            var cur = IntArray(b.length + 1)
            for (i in 1..a.length) {
                cur[0] = i
                var rowMin = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = min(min(prev[j] + 1, cur[j - 1] + 1), prev[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = min(v, prev2[j - 2] + 1)
                    cur[j] = v
                    if (v < rowMin) rowMin = v
                }
                if (rowMin > k) return k + 1
                val t = prev2; prev2 = prev; prev = cur; cur = t
            }
            return prev[b.length]
        }

        /** Smallest distance between [a] and any prefix of [b] (for a word still being typed). */
        private fun prefixDistance(a: String, b: String, k: Int): Int {
            var best = k + 1
            for (end in max(1, a.length - k)..b.length) {
                best = min(best, distance(a, b.substring(0, end), k))
                if (best == 0) break
            }
            return best
        }

        private fun abs(x: Int) = if (x < 0) -x else x
    }
}
