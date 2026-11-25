package com.auroratv.recommend

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.playback.ContentKind
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * How alike two titles are, from metadata the server already has: no model, no catalogue download,
 * no outside service. A title only qualifies with a clear reason, and generic popularity can't
 * carry it.
 *
 * Score = (content similarity + small bonuses) × kind factor × audience factor, where
 * - **content similarity** mixes genre overlap (each genre weighted by how rare it is among the
 *   candidates, so "Comedy" counts less than "Sci-Fi & Fantasy"; "Animation"/"Anime" don't count,
 *   the kind handles them), keyword/tag overlap and how much the two descriptions share
 *   (TF-IDF over their distinctive words: "abilities", "school", "detective" …);
 * - **bonuses**: Jellyfin's own Similar list picked it (it also weighs cast and crew), or a
 *   studio in common;
 * - **kind factor** ([ContentKind]): 1 for the same kind; anime ↔ Western animation 0.35,
 *   animation ↔ live action 0.3, anime ↔ live action 0.2, so a cross-kind title needs a very strong
 *   match to appear;
 * - **audience factor**: a children's title for a teen/adult seed (or the reverse) counts half.
 * Only titles above [MIN_SCORE] with a real reason (shared genres, tags or description) are kept;
 * a short row of close matches beats a full row of loose ones.
 */
object Similarity {
    enum class Audience { Kids, Family, Teen, Adult, Unknown }

    /** One title's comparable features. */
    class Doc(val item: BaseItemDto) {
        val kind = ContentKind.of(item)
        val genres = item.genres.map { it.lowercase().trim() }.filter { it !in FORMAT }.toSet()
        val tags = item.tags.map { it.lowercase().trim() }.filter { it.isNotEmpty() && it !in FORMAT }.toSet()
        val words = tokens(item.overview.orEmpty())
        val audience = audience(item)
        val studios = item.studios.mapNotNull { it.name?.lowercase() }.toSet()
    }

    const val MIN_SCORE = 0.25
    private val FORMAT = setOf("animation", "anime", "animated", "cartoon", "adult animation")

    /** [candidates] most like [seed], best first, only those that qualify. [fromServer]: ids Jellyfin's Similar returned. */
    fun rank(seed: BaseItemDto, candidates: List<BaseItemDto>, fromServer: Set<String>, limit: Int): List<BaseItemDto> {
        val s = Doc(seed)
        val docs = candidates.filter { it.id != seed.id }.distinctBy { it.id }.map(::Doc)
        val idf = Idf(docs + s)
        return docs.asSequence()
            .map { d -> d to score(s, d, idf, d.item.id in fromServer) }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .take(limit).map { it.first.item }.toList()
    }

    /** The similarity score of [b] to [a] (0 when there's no clear reason). */
    fun score(a: Doc, b: Doc, idf: Idf, fromServer: Boolean = false): Double {
        val g = weightedJaccard(a.genres, b.genres, idf::genre)
        val t = if (a.tags.isEmpty() || b.tags.isEmpty()) null else jaccard(a.tags, b.tags)
        val x = if (a.words.isEmpty() || b.words.isEmpty()) null else cosine(a.words, b.words, idf::word)
        val reason = g >= 0.25 || (t ?: 0.0) >= 0.2 || (x ?: 0.0) >= 0.15
        if (!reason) return 0.0
        val base = when {
            t != null && x != null -> 0.45 * g + 0.2 * t + 0.35 * x.boost()
            t != null -> 0.7 * g + 0.3 * t
            x != null -> 0.55 * g + 0.45 * x.boost()
            else -> g
        }
        val bonus = (if (fromServer) 0.06 else 0.0) + (if (a.studios.any { it in b.studios }) 0.06 else 0.0)
        return (base + bonus) * kindFactor(a.kind, b.kind) * audienceFactor(a.audience, b.audience) +
            0.005 * (b.item.communityRating ?: 0.0)
    }

    /** Descriptions rarely share more than a third of their weight even when closely related. */
    private fun Double.boost() = (this * 2).coerceAtMost(1.0)

    fun kindFactor(a: ContentKind, b: ContentKind): Double = when {
        a == b -> 1.0
        setOf(a, b) == setOf(ContentKind.Anime, ContentKind.Animation) -> 0.35
        setOf(a, b) == setOf(ContentKind.Animation, ContentKind.LiveAction) -> 0.3
        else -> 0.2
    }

    fun audienceFactor(a: Audience, b: Audience): Double = when {
        a == Audience.Unknown || b == Audience.Unknown -> 1.0
        (a == Audience.Kids) != (b == Audience.Kids) && (a in GROWN || b in GROWN) -> 0.5
        (a == Audience.Family && b == Audience.Adult) || (a == Audience.Adult && b == Audience.Family) -> 0.8
        else -> 1.0
    }
    private val GROWN = setOf(Audience.Teen, Audience.Adult)

    fun audience(item: BaseItemDto): Audience {
        val r = item.officialRating?.uppercase()?.replace(" ", "")
        val fromRating = when (r) {
            null, "" -> null
            "TV-Y", "TV-Y7", "TV-Y7-FV", "0" -> Audience.Kids
            "G", "U", "AL", "6", "7", "10", "TV-G", "PG", "TV-PG", "PG-12" -> Audience.Family
            "PG-13", "TV-14", "12", "12A", "13", "14", "15", "M" -> Audience.Teen
            "R", "TV-MA", "NC-17", "16", "18", "R18", "MA15+", "R18+" -> Audience.Adult
            else -> null
        }
        if (fromRating != null) return fromRating
        val g = item.genres.map { it.lowercase() }
        return when {
            g.any { it == "kids" || it == "children" } -> Audience.Kids
            "family" in g -> Audience.Family
            else -> Audience.Unknown
        }
    }

    /** Genres that say something about content (not "Animation"/"Anime", which is the kind). */
    fun contentGenres(item: BaseItemDto): List<String> = item.genres.filter { it.lowercase().trim() !in FORMAT }

    /** Rarity weights over a set of titles: rare genres and words say more than common ones. */
    class Idf(docs: List<Doc>) {
        private val n = docs.size.coerceAtLeast(1).toDouble()
        private val genreDf = HashMap<String, Int>().also { m -> docs.forEach { d -> d.genres.forEach { m[it] = (m[it] ?: 0) + 1 } } }
        private val wordDf = HashMap<String, Int>().also { m -> docs.forEach { d -> d.words.forEach { m[it] = (m[it] ?: 0) + 1 } } }
        fun genre(g: String) = ln(1.0 + n / (genreDf[g] ?: 1))
        fun word(w: String) = ln(1.0 + n / (wordDf[w] ?: 1))
    }

    private fun weightedJaccard(a: Set<String>, b: Set<String>, w: (String) -> Double): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val inter = a.filter { it in b }.sumOf(w)
        val union = (a + b).sumOf(w)
        return if (union <= 0) 0.0 else inter / union
    }

    private fun jaccard(a: Set<String>, b: Set<String>): Double {
        val inter = a.count { it in b }
        return if (inter == 0) 0.0 else inter.toDouble() / (a.size + b.size - inter)
    }

    private fun cosine(a: Set<String>, b: Set<String>, w: (String) -> Double): Double {
        val dot = a.filter { it in b }.sumOf { w(it).let { x -> x * x } }
        if (dot == 0.0) return 0.0
        val na = a.sumOf { w(it).let { x -> x * x } }; val nb = b.sumOf { w(it).let { x -> x * x } }
        return dot / sqrt(na * nb)
    }

    /** Distinctive words of a description: lower case, no stop words, plurals folded. */
    fun tokens(text: String): Set<String> =
        text.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            .filter { it.length >= 3 && it !in STOP && it.any(Char::isLetter) }
            .map(::stem)
            .filter { it.length >= 3 && it !in STOP }
            .toSet()

    private fun stem(w: String): String = when {
        w.length > 5 && w.endsWith("ies") -> w.dropLast(3) + "y"
        w.length > 5 && w.endsWith("sses") -> w.dropLast(2)
        w.length > 4 && w.endsWith("s") && !w.endsWith("ss") && !w.endsWith("us") && !w.endsWith("is") -> w.dropLast(1)
        else -> w
    }

    private val STOP = setOf(
        "the", "and", "for", "with", "his", "her", "their", "they", "them", "this", "that", "from", "into", "who", "whom", "whose",
        "when", "where", "what", "which", "while", "after", "before", "about", "but", "are", "was", "were", "has", "have", "had",
        "been", "being", "its", "it's", "one", "two", "all", "out", "our", "your", "you", "she", "him", "not", "can", "will",
        "must", "more", "most", "than", "then", "there", "these", "those", "only", "own", "over", "under", "also", "just",
        "very", "even", "ever", "every", "each", "any", "some", "such", "life", "new", "world", "find", "finds", "get", "gets",
        "becomes", "become", "story", "series", "film", "movie", "season", "episode", "show", "both", "between", "through",
        "around", "against", "during", "without", "within", "upon", "himself", "herself", "themselves", "other", "others",
        "now", "how", "why", "yet", "may", "might", "would", "could", "should", "take", "takes", "make", "makes", "set",
        "begin", "begins", "way", "back", "still", "many", "much", "first", "last", "day", "days", "year", "years", "time",
        "together", "young", "man", "woman", "people", "where", "here", "off", "down", "never", "however", "soon", "goes",
    )
}
