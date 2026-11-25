package com.auroratv.jellyfin

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.platform.FileStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Seasons that exist only in an episode order (a DVD order with more seasons than the server has):
 * `vsn~<series>~<number>`. Images and anything the server is asked about use the series.
 */
object OrderSeasonIds {
    const val PREFIX = "vsn~"
    fun of(seriesId: String, number: Int) = "$PREFIX$seriesId~$number"
    fun isVirtual(id: String) = id.startsWith(PREFIX)
    fun seriesOf(id: String): String = id.removePrefix(PREFIX).substringBeforeLast('~')
}

/** One episode of an alternative order, by its aired season and number (TMDB numbering) and TMDB id. */
@Serializable
data class GroupEpisode(
    @SerialName("s") val season: Int,
    @SerialName("e") val episode: Int,
    @SerialName("id") val tmdbId: Long? = null,
    @SerialName("t") val name: String? = null,
)

/** One season of an alternative order: its number there, its name ("Season 1", "Volume 2"), its episodes in order. */
@Serializable
data class GroupSeason(
    @SerialName("n") val number: Int,
    @SerialName("t") val name: String? = null,
    @SerialName("e") val episodes: List<GroupEpisode>,
)

/**
 * An alternative episode order from a metadata source (a TMDB episode group): DVD, production,
 * a documented intended order... [type] is TMDB's (1 original air date, 2 absolute, 3 DVD, 4 digital,
 * 5 story arc, 6 production, 7 TV).
 */
@Serializable
data class SourceOrder(
    @SerialName("id") val id: String,
    @SerialName("t") val name: String,
    @SerialName("y") val type: Int,
    @SerialName("s") val seasons: List<GroupSeason>,
    @SerialName("src") val source: String = "TMDB",
) {
    val kind: String get() = kindOf(type, name)

    companion object {
        /** What the source calls this order, in words ("DVD order", "Intended order"...). Never guessed. */
        fun kindOf(type: Int, name: String): String = when {
            name.contains("intended", ignoreCase = true) -> "Intended order"
            type == 3 -> "DVD order"
            type == 6 -> "Production order"
            type == 2 -> "Absolute order"
            type == 4 -> "Digital order"
            type == 5 -> "Story arc"
            type == 7 -> "TV order"
            type == 1 -> "Original air date"
            else -> "Alternative order"
        }
    }
}

/** An order the source lists for a series, before its episodes are fetched. */
@Serializable
data class SourceOrderSummary(
    @SerialName("id") val id: String,
    @SerialName("t") val name: String,
    @SerialName("y") val type: Int,
    @SerialName("c") val episodeCount: Int = 0,
)

/** A season of a hand-made order: its number, optional name, and its episodes (item ids, virtual ones too). */
@Serializable
data class CustomSeason(
    @SerialName("n") val number: Int,
    @SerialName("t") val name: String? = null,
    @SerialName("i") val ids: List<String>,
)

/**
 * Everything kept about one series' order on this device (per server, shared by its profiles: an
 * order describes the show, not anyone's viewing).
 *
 * [mode]: [AUTO] (the best verified source order, when automatic ordering is on), [JELLYFIN] (the
 * server's own order), `group:<id>` (one source order), or [CUSTOM] ([custom]).
 * [sources]/[sourcesAtMs]: the orders the source lists (null: never looked); [details]: the ones
 * fetched and checked against the library; [rejected]: ones whose numbering didn't match it.
 */
@Serializable
data class SeriesOrder(
    @SerialName("m") val mode: String = AUTO,
    @SerialName("c") val custom: List<CustomSeason> = emptyList(),
    @SerialName("l") val sources: List<SourceOrderSummary>? = null,
    @SerialName("la") val sourcesAtMs: Long = 0,
    @SerialName("d") val details: Map<String, SourceOrder> = emptyMap(),
    @SerialName("r") val rejected: Map<String, String> = emptyMap(),
    /** Display labels from the last arrangement (id → "season|number|seasonId|seasonName") for cards elsewhere. */
    @SerialName("lb") val labels: Map<String, String> = emptyMap(),
) {
    companion object {
        const val AUTO = "auto"
        const val JELLYFIN = "jellyfin"
        const val CUSTOM = "custom"
        const val GROUP = "group:"
    }
}

/** `episode-order-<server>.json`: [SeriesOrder] per series id. Small, read once, written on change. */
@OptIn(ExperimentalAtomicApi::class)
class OrderStore(private val files: FileStore, serverKey: String) {
    private val file = "episode-order-${serverKey.filter { it.isLetterOrDigit() || it == '-' }.take(24)}.json"
    private val serializer = MapSerializer(String.serializer(), SeriesOrder.serializer())
    private val map = AtomicReference(load())

    private fun load(): Map<String, SeriesOrder> = runCatching { files.read(file)?.let { AuroraJson.decodeFromString(serializer, it) } }
        .onFailure { Log.w("EpisodeOrder", "Episode orders unreadable, starting fresh: ${it.message}") }.getOrNull() ?: emptyMap()

    operator fun get(seriesId: String): SeriesOrder? = map.load()[seriesId]
    val isEmpty: Boolean get() = map.load().isEmpty()

    fun update(seriesId: String, f: (SeriesOrder) -> SeriesOrder) {
        while (true) {
            val old = map.load()
            val before = old[seriesId] ?: SeriesOrder()
            val after = f(before)
            if (after == before && old.containsKey(seriesId)) return
            val next = old + (seriesId to after)
            if (map.compareAndSet(old, next)) {
                runCatching { files.write(file, AuroraJson.encodeToString(serializer, next)) }.onFailure { Log.w("EpisodeOrder", "Couldn't save episode orders: ${it.message}") }
                return
            }
        }
    }
}

/** Where one episode goes: season number and a position to sort by within it. */
data class Slot(val season: Int, val position: Double)

/** How one series is arranged: which order, and where each episode goes. */
sealed interface OrderPlan {
    /** Short words for the order ("DVD order", "Custom order"). */
    val label: String

    /** An order from a metadata source, matched by aired season and episode number. */
    data class Source(val order: SourceOrder) : OrderPlan {
        override val label get() = order.kind
        private val byNumber: Map<Pair<Int, Int>, Slot> = buildMap {
            order.seasons.forEach { s -> s.episodes.forEachIndexed { i, e -> putIfAbsent(e.season to e.episode, Slot(s.number, i.toDouble())) } }
        }
        fun slot(item: BaseItemDto): Slot? {
            val s = item.parentIndexNumber ?: return null
            val e = item.indexNumber ?: return null
            return byNumber[s to e]
        }
        fun seasonName(number: Int): String? = order.seasons.firstOrNull { it.number == number }?.name
    }

    /** A hand-made order, by item id. */
    data class Custom(val seasons: List<CustomSeason>) : OrderPlan {
        override val label get() = "Custom order"
        private val byId: Map<String, Slot> = buildMap {
            seasons.forEach { s -> s.ids.forEachIndexed { i, id -> putIfAbsent(id, Slot(s.number, i.toDouble())) } }
        }
        fun slot(item: BaseItemDto): Slot? {
            byId[item.id]?.let { return it }
            // A file split after the order was made: its parts take the file's place, in order.
            VirtualIds.part(item.id)?.let { k -> byId[VirtualIds.serverId(item.id)]?.let { return it.copy(position = it.position + (k + 1) / 100.0) } }
            // A split removed since: the file takes its first part's place.
            if (!VirtualIds.isVirtual(item.id)) byId[VirtualIds.of(item.id, 0)]?.let { return it }
            return null
        }
        fun seasonName(number: Int): String? = seasons.firstOrNull { it.number == number }?.name
    }
}

/** A series' episodes arranged in an order: its seasons and, per season, its episodes. */
data class Arrangement(
    val seriesId: String,
    val plan: OrderPlan,
    val seasons: List<BaseItemDto>,
    val episodesBySeason: Map<String, List<BaseItemDto>>,
) {
    /** Every episode in viewing order. */
    val all: List<BaseItemDto> by lazy { seasons.flatMap { episodesBySeason[it.id].orEmpty() } }

    /** The arranged season holding [seasonId] (an arranged season's id, or the server's id for that number). */
    fun season(seasonId: String): BaseItemDto? = seasons.firstOrNull { it.id == seasonId }

    fun indexOf(id: String): Int = all.indexOfFirst { it.id == id }
}

/**
 * Episode orders: shows whose episodes are listed, browsed and played in an order other than the
 * server's (a DVD or intended order from TMDB, or one made by hand), without changing the server,
 * the files or anyone's progress. Episodes keep their ids (so the right video always plays and
 * progress stays attached); only where they appear, and the numbers shown, change.
 *
 * Off for a series unless an order applies: [plan] is null, and nothing anywhere changes.
 */
class EpisodeOrders(
    val store: OrderStore,
    /** "Automatic episode order" setting: verified source orders apply by themselves. */
    private val autoEnabled: () -> Boolean = { true },
) {
    /** The order [seriesId] is shown in, or null for the server's own. */
    fun plan(seriesId: String): OrderPlan? {
        val o = store[seriesId] ?: return null
        return when {
            o.mode == SeriesOrder.CUSTOM && o.custom.isNotEmpty() -> OrderPlan.Custom(o.custom)
            o.mode.startsWith(SeriesOrder.GROUP) -> o.details[o.mode.removePrefix(SeriesOrder.GROUP)]?.takeIf { it.id !in o.rejected }?.let { OrderPlan.Source(it) }
            o.mode == SeriesOrder.AUTO && autoEnabled() -> automatic(o)?.let { OrderPlan.Source(it) }
            else -> null
        }
    }

    /** True when [seriesId] might be shown in another order (cheap; no lookups). */
    fun active(seriesId: String?): Boolean = seriesId != null && plan(seriesId) != null

    // ---- Arranging --------------------------------------------------------------------------------

    /**
     * [all] (every episode of the series, in the server's order, split files already shown as their
     * episodes) arranged by [plan]. Episodes the order doesn't mention stay next to the episode they
     * follow on the server, in their own season. Seasons the server has keep its id and pictures; a
     * season only the order has gets a [OrderSeasonIds] id. Shown numbers become the arranged ones.
     */
    fun arrange(seriesId: String, plan: OrderPlan, serverSeasons: List<BaseItemDto>, all: List<BaseItemDto>): Arrangement {
        val list = all.distinctBy { it.id }
        fun slotOf(item: BaseItemDto): Slot? = when (plan) {
            is OrderPlan.Source -> plan.slot(item)
            is OrderPlan.Custom -> plan.slot(item)
        }
        val slots = arrayOfNulls<Slot>(list.size)
        list.forEachIndexed { i, item -> slots[i] = slotOf(item) }
        // Not in the order: beside its neighbour on the server (the one before it in the same season,
        // else the one after), in its own season; with no placed neighbour, at the start of its season.
        list.forEachIndexed { i, item ->
            if (slots[i] != null) return@forEachIndexed
            val season = item.parentIndexNumber ?: 1
            val before = (i - 1 downTo 0).firstOrNull { j -> list[j].parentIndexNumber == item.parentIndexNumber && slotOf(list[j])?.season == season }
            val after = (i + 1 until list.size).firstOrNull { j -> list[j].parentIndexNumber == item.parentIndexNumber && slotOf(list[j])?.season == season }
            slots[i] = when {
                before != null -> Slot(season, slotOf(list[before])!!.position + (i - before) / 1000.0)
                after != null -> Slot(season, slotOf(list[after])!!.position - (after - i) / 1000.0)
                else -> Slot(season, -1_000_000.0 + i)
            }
        }
        val placed = list.indices.sortedWith(compareBy({ slots[it]!!.season.let { s -> if (s == 0) Int.MAX_VALUE else s } }, { slots[it]!!.position }, { it }))
        val bySeason = LinkedHashMap<Int, MutableList<BaseItemDto>>()
        placed.forEach { i -> bySeason.getOrPut(slots[i]!!.season) { mutableListOf() }.add(list[i]) }

        val seasons = ArrayList<BaseItemDto>()
        val episodes = HashMap<String, List<BaseItemDto>>()
        val serverByNumber = serverSeasons.filter { it.indexNumber != null }.associateBy { it.indexNumber!! }
        val seriesName = list.firstOrNull()?.seriesName
        bySeason.forEach { (number, items) ->
            val named = when (plan) {
                is OrderPlan.Source -> plan.seasonName(number)
                is OrderPlan.Custom -> plan.seasonName(number)
            }
            val server = serverByNumber[number]
            val season = server?.let { s -> if (named != null && !isPlainSeasonName(named, number)) s.copy(name = named) else s }
                ?: BaseItemDto(
                    id = OrderSeasonIds.of(seriesId, number), type = "Season", name = named ?: if (number == 0) "Specials" else "Season $number",
                    indexNumber = number, seriesId = seriesId, seriesName = seriesName, isFolder = true,
                )
            seasons += season
            var n = 0
            episodes[season.id] = items.map { ep ->
                val first = ++n
                val span = (ep.indexNumberEnd ?: ep.indexNumber)?.let { end -> ep.indexNumber?.let { end - it } } ?: 0
                if (span > 0) n += span
                ep.copy(parentIndexNumber = number, indexNumber = first, indexNumberEnd = if (span > 0) first + span else null, seasonId = season.id, seasonName = season.name)
            }
        }
        return Arrangement(seriesId, plan, seasons, episodes)
    }

    /** Keeps the shown numbers of [arrangement] so cards elsewhere (Continue Watching) can show them. */
    fun remember(arrangement: Arrangement) {
        val labels = arrangement.all.associate { ep -> ep.id to "${ep.parentIndexNumber}|${ep.indexNumber}|${ep.indexNumberEnd ?: ""}|${ep.seasonId}|${ep.seasonName.orEmpty().replace('|', ' ')}" }
        store.update(arrangement.seriesId) { if (it.labels == labels) it else it.copy(labels = labels) }
    }

    /** [item] with the season and number its series' order gives it (as last arranged), or as it is. */
    fun relabel(item: BaseItemDto): BaseItemDto {
        if (!item.isEpisode) return item
        val sid = item.seriesId ?: return item
        if (plan(sid) == null) return item
        val label = store[sid]?.labels?.get(item.id) ?: return item
        val p = label.split('|')
        if (p.size < 5) return item
        return item.copy(
            parentIndexNumber = p[0].toIntOrNull() ?: item.parentIndexNumber,
            indexNumber = p[1].toIntOrNull() ?: item.indexNumber,
            indexNumberEnd = p[2].toIntOrNull(),
            seasonId = p[3].ifBlank { item.seasonId },
            seasonName = p[4].ifBlank { item.seasonName },
        )
    }

    /**
     * Next Up in an order: the first episode not watched after the one watched most recently
     * (by date; else the furthest watched), skipping specials left out of the seasons. Null when
     * nothing has been watched, or everything after it has. [allowStarted]: an episode already
     * started counts (a series' own Play button resumes it; Home's Next Up leaves it to Continue Watching).
     */
    fun nextUp(arrangement: Arrangement, allowStarted: Boolean): BaseItemDto? {
        val all = arrangement.all
        val watched = all.withIndex().filter { (_, e) -> e.userData?.played == true || (e.userData?.playbackPositionTicks ?: 0) > 0 }
        if (watched.isEmpty()) return null
        val dated = watched.filter { it.value.userData?.lastPlayedDate != null }
        val last = (if (dated.isNotEmpty()) dated.maxBy { it.value.userData!!.lastPlayedDate!! } else watched.last())
        // The one played last may be unfinished: then it is the next one.
        if (last.value.userData?.played != true && allowStarted && (last.value.parentIndexNumber ?: 1) > 0) return last.value
        return all.drop(last.index + 1).firstOrNull { e ->
            e.userData?.played != true && e.locationType != "Virtual" && (e.parentIndexNumber ?: 1) > 0 &&
                (allowStarted || (e.userData?.playbackPositionTicks ?: 0L) == 0L)
        }
    }

    // ---- Source orders ----------------------------------------------------------------------------

    /** The source order used automatically: a documented intended order, else the DVD order (verified ones only). */
    private fun automatic(o: SeriesOrder): SourceOrder? {
        val ok = o.details.values.filter { it.id !in o.rejected }
        return ok.firstOrNull { it.name.contains("intended", ignoreCase = true) } ?: ok.firstOrNull { it.type == 3 }
    }

    /** The automatic order's words for a series ("DVD order"), or null when none applies. */
    fun automaticLabel(seriesId: String): String? = store[seriesId]?.let { automatic(it) }?.kind

    /** Source orders worth fetching for automatic use, from the list the source gave. */
    fun candidates(sources: List<SourceOrderSummary>): List<SourceOrderSummary> {
        val intended = sources.filter { it.name.contains("intended", ignoreCase = true) }
        val dvd = sources.filter { it.type == 3 }.sortedByDescending { it.episodeCount }
        return (intended + dvd).distinctBy { it.id }.take(3)
    }

    companion object {
        /** Lookups of a series' orders are repeated after this long (orders on TMDB rarely change). */
        const val SOURCES_TTL_MS = 7L * 24 * 3600 * 1000

        private fun isPlainSeasonName(name: String, number: Int) =
            name.equals("Season $number", ignoreCase = true) || (number == 0 && name.equals("Specials", ignoreCase = true))

        private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

        /**
         * Whether [order] describes this library: its aired numbers must find the library's episodes,
         * and the episodes found must carry the same titles (a check that the numbering is the same
         * one, not the way episodes are matched). Returns null when it fits, else why not.
         */
        fun verify(order: SourceOrder, library: List<BaseItemDto>): String? {
            val eps = library.filter { it.isEpisode && it.locationType != "Virtual" && !VirtualIds.isVirtual(it.id) }
            val regular = eps.filter { (it.parentIndexNumber ?: 0) > 0 }
            if (regular.isEmpty()) return "no episodes to compare"
            val inOrder = order.seasons.flatMap { it.episodes }
            val byNumber = inOrder.groupBy { it.season to it.episode }
            val found = regular.filter { ep ->
                val s = ep.parentIndexNumber ?: return@filter false
                val first = ep.indexNumber ?: return@filter false
                (first..(ep.indexNumberEnd ?: first)).any { byNumber.containsKey(s to it) }
            }
            if (found.size < regular.size * 0.9) return "only ${found.size} of ${regular.size} episodes are in it"
            var compared = 0
            var same = 0
            found.forEach { ep ->
                val s = ep.parentIndexNumber!!
                val first = ep.indexNumber!!
                val names = (first..(ep.indexNumberEnd ?: first)).mapNotNull { byNumber[s to it]?.firstOrNull()?.name }.filter { it.isNotBlank() }
                val mine = norm(ep.name)
                if (names.isEmpty() || mine.isEmpty()) return@forEach
                compared++
                if (names.any { n -> norm(n).let { it == mine || (it.length >= 4 && mine.contains(it)) } }) same++
            }
            if (compared >= 3 && same < compared * 0.8) return "titles differ for ${compared - same} of $compared episodes (different numbering)"
            return null
        }

        /** Season number for a source group: "Season 3" → 3, "Specials" → 0, else its place among the others. */
        fun seasonNumber(name: String, indexAmongRegular: Int): Int {
            Regex("""season\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)?.let { return it.groupValues[1].toInt() }
            if (name.contains("special", ignoreCase = true)) return 0
            return indexAmongRegular + 1
        }
    }
}
