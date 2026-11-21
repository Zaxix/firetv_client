package com.auroratv.jellyfin

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.ChapterInfo
import com.auroratv.jellyfin.model.UserItemData
import com.auroratv.jellyfin.model.msToTicks
import com.auroratv.jellyfin.model.ticksToMs
import com.auroratv.platform.FileStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Ids of virtual episodes: part [k] of the server item [physical] is `vep~<physical>~<k>`. The id
 * says which file to play, so anything that talks to the server ([serverId]) needs no lookup.
 */
object VirtualIds {
    const val PREFIX = "vep~"
    fun of(physicalId: String, part: Int) = "$PREFIX$physicalId~$part"
    fun isVirtual(id: String) = id.startsWith(PREFIX)
    /** The server's item behind [id]: a virtual episode's file, or [id] itself. */
    fun serverId(id: String): String = if (isVirtual(id)) id.removePrefix(PREFIX).substringBeforeLast('~') else id
    fun part(id: String): Int? = if (isVirtual(id)) id.substringAfterLast('~').toIntOrNull() else null

    /** Image tag keys on a virtual episode whose thumbnail is the chapter image at its start. */
    const val CHAPTER_TAG = "SfChapter"
    const val CHAPTER_INDEX = "SfChapterIndex"
    /** Index in the file's chapter list of a virtual episode's first chapter (its chapters are renumbered from 0). */
    const val CHAPTER_BASE = "SfChapterBase"
}

/** One episode inside a file: [startMs]–[endMs] of the file. [entryId]: the server's own entry for it (a shared-file group). */
@Serializable
data class SplitPart(
    @SerialName("s") val startMs: Long,
    @SerialName("e") val endMs: Long,
    @SerialName("n") val number: Int? = null,
    @SerialName("t") val title: String? = null,
    @SerialName("i") val entryId: String? = null,
    /** The chapter image at its start (index and tag), kept so lists without chapters still show it. */
    @SerialName("ci") val thumbChapter: Int? = null,
    @SerialName("ct") val thumbTag: String? = null,
)

/**
 * How one file divides into episodes. [parts] empty: the file was looked at and holds [count]
 * episodes, but where they start isn't known (so it isn't split; it can be set by hand).
 * [runtimeTicks] ties it to that exact file: a replaced file (different length) doesn't inherit it.
 */
@Serializable
data class EpisodeSplit(
    @SerialName("rt") val runtimeTicks: Long,
    @SerialName("p") val parts: List<SplitPart> = emptyList(),
    @SerialName("m") val manual: Boolean = false,
    @SerialName("c") val count: Int = 0,
    @SerialName("why") val reason: String? = null,
    /** Shared-file group: the server's entries for this file, in episode order. */
    @SerialName("g") val entries: List<String> = emptyList(),
) {
    val isSplit get() = parts.size >= 2
    /** Same file as [item] (length within 2 s): a mapping never follows a replaced file. */
    fun fits(item: BaseItemDto): Boolean = item.runTimeTicks?.let { kotlin.math.abs(it - runtimeTicks) <= 2_000L.msToTicks() } ?: true
}

/** Where a virtual episode sits in its file. */
data class Window(val startMs: Long, val endMs: Long) { val lengthMs get() = endMs - startMs }

/**
 * Episode splits for one server, shared by its profiles (they describe files, not viewing):
 * `episode-splits-<server>.json`, keyed by the item that holds the file (for a shared-file group,
 * its first entry). Small, read once, written on change.
 */
@OptIn(ExperimentalAtomicApi::class)
class SplitStore(private val files: FileStore, serverKey: String) {
    private val file = "episode-splits-${serverKey.filter { it.isLetterOrDigit() || it == '-' }.take(24)}.json"
    private val serializer = MapSerializer(String.serializer(), EpisodeSplit.serializer())
    private val map = AtomicReference(load())

    private fun load(): Map<String, EpisodeSplit> = runCatching { files.read(file)?.let { AuroraJson.decodeFromString(serializer, it) } }
        .onFailure { Log.w("Splits", "Episode splits unreadable, starting fresh: ${it.message}") }.getOrNull() ?: emptyMap()

    val isEmpty: Boolean get() = map.load().isEmpty()
    /** Any file actually split (not only looked at). */
    val anySplit: Boolean get() = map.load().values.any { it.isSplit }

    operator fun get(key: String): EpisodeSplit? = map.load()[key]

    /** The group key and part for a shared-file entry, if [entryId] is in one. */
    fun forEntry(entryId: String): Pair<String, EpisodeSplit>? =
        map.load().entries.firstOrNull { (_, s) -> entryId in s.entries }?.let { it.key to it.value }

    fun put(key: String, split: EpisodeSplit) = change { it + (key to split) }
    fun remove(key: String) = change { it - key }

    private inline fun change(f: (Map<String, EpisodeSplit>) -> Map<String, EpisodeSplit>) {
        while (true) {
            val old = map.load()
            val next = f(old)
            if (map.compareAndSet(old, next)) {
                runCatching { files.write(file, AuroraJson.encodeToString(serializer, next)) }.onFailure { Log.w("Splits", "Couldn't save episode splits: ${it.message}") }
                return
            }
        }
    }
}

/**
 * Virtual Episode Splitting: several episodes stored in one video file are shown and played as
 * separate episodes, without touching the file. Two cases:
 *
 * - **One server entry for several episodes** (a file named `S01E01-E02`): the entry is replaced in
 *   every list by one virtual episode per part, `vep~<item>~<k>`, playing that stretch of the file.
 * - **Several server entries for one file**: each entry keeps its id and metadata, and plays only its
 *   own stretch.
 *
 * Progress is kept per profile on the device ([watch]); the server's progress for the file is never
 * moved (one position can't describe two episodes). The server learns only "watched": an entry of a
 * group when its part is finished, a split file when all its parts are.
 */
class VirtualEpisodes(
    val splits: SplitStore,
    private val watch: com.auroratv.profiles.LocalWatchStore,
) {
    // ---- Windows ----------------------------------------------------------------------------------

    /** The stretch of the file [id] plays, or null for an ordinary item. */
    fun window(id: String): Window? {
        VirtualIds.part(id)?.let { k ->
            val split = splits[VirtualIds.serverId(id)] ?: return null
            return split.parts.getOrNull(k)?.let { Window(it.startMs, it.endMs) }
        }
        val (_, split) = splits.forEntry(id) ?: return null
        if (!split.isSplit) return null
        return split.parts.firstOrNull { it.entryId == id }?.let { Window(it.startMs, it.endMs) }
    }

    /** True when [id]'s progress is kept here rather than on the server. */
    fun handles(id: String): Boolean = window(id) != null

    // ---- Lists ------------------------------------------------------------------------------------

    /**
     * [items] with split files shown as their episodes and shared-file entries playing their own part.
     * Unsplit items are returned as they are (the same objects, no work when nothing is split).
     */
    fun expand(items: List<BaseItemDto>): List<BaseItemDto> {
        if (!splits.anySplit) return items
        return items.flatMap { item ->
            if (!item.isEpisode || VirtualIds.isVirtual(item.id)) return@flatMap listOf(item)
            val own = splits[item.id]?.takeIf { it.isSplit && it.fits(item) }
            when {
                own != null && own.entries.isEmpty() -> own.parts.indices.map { build(item, it, own) }
                else -> listOf(entryView(item) ?: item)
            }
        }
    }

    /** A shared-file entry, with its part's length and this profile's progress; null if [item] isn't one. */
    fun entryView(item: BaseItemDto): BaseItemDto? {
        val (_, split) = splits.forEntry(item.id) ?: return null
        if (!split.isSplit || !split.fits(item)) return null
        val part = split.parts.firstOrNull { it.entryId == item.id } ?: return null
        val dto = item.copy(runTimeTicks = (part.endMs - part.startMs).msToTicks(), chapters = shiftChapters(item.chapters, part).first)
        return dto.copy(userData = userData(dto, item, part))
    }

    /** Virtual episode [k] of the split file [file]. */
    fun build(file: BaseItemDto, k: Int, split: EpisodeSplit): BaseItemDto {
        val part = split.parts[k]
        val names = splitNames(file.name, split.parts.size)
        // The file's own name stands for the first episode, unless it's only the file name
        // ("Show - S01E01-E02"), which describes neither episode.
        val fileNameOnly = file.name?.let { EPISODE_RANGE.containsMatchIn(it) } == true
        val title = part.title?.takeIf { it.isNotBlank() } ?: names?.getOrNull(k) ?: if (k == 0 && !fileNameOnly) file.name else null
        val (chapters, base) = shiftChapters(file.chapters, part)
        // The chapter image where this part starts is its own thumbnail (the file's picture is the first part's).
        val thumb = part.thumbTag?.let { tag -> part.thumbChapter?.let { it to tag } } ?: chapterAt(file.chapters, part.startMs)
        val tags = file.imageTags.toMutableMap()
        if (k > 0 && thumb != null) { tags[VirtualIds.CHAPTER_TAG] = thumb.second; tags[VirtualIds.CHAPTER_INDEX] = thumb.first.toString() }
        if (base > 0) tags[VirtualIds.CHAPTER_BASE] = base.toString()
        val dto = file.copy(
            id = VirtualIds.of(file.id, k),
            name = title,
            // The file's description is the first episode's (the server takes the first one's metadata);
            // the others get none rather than a wrong one, unless names said otherwise.
            overview = if (k == 0) file.overview else null,
            indexNumber = part.number ?: file.indexNumber?.plus(k),
            indexNumberEnd = null,
            runTimeTicks = (part.endMs - part.startMs).msToTicks(),
            chapters = chapters,
            imageTags = tags,
        )
        return dto.copy(userData = userData(dto, file, part))
    }

    /**
     * This profile's progress for a part. Before it has any, the file's own state from the server
     * carries over: a watched file means watched parts, and a resume point inside a part is that
     * part's resume point.
     */
    private fun userData(dto: BaseItemDto, file: BaseItemDto, part: SplitPart): UserItemData {
        val mine = watch.userData(dto) // also notes the part's runtime for progress maths
        if (watch.has(dto.id)) return mine.copy(isFavorite = file.userData?.isFavorite == true)
        val server = file.userData ?: return mine
        val pos = server.playbackPositionTicks.ticksToMs()
        val inside = !server.played && pos > part.startMs && pos < part.endMs
        val rel = if (inside) (pos - part.startMs).msToTicks() else 0L
        val len = (part.endMs - part.startMs).msToTicks()
        return UserItemData(
            playbackPositionTicks = rel,
            playedPercentage = if (rel > 0 && len > 0) rel * 100.0 / len else null,
            played = server.played,
            isFavorite = server.isFavorite,
            playCount = if (server.played) 1 else 0,
            lastPlayedDate = if (inside) server.lastPlayedDate else null,
        )
    }

    // ---- Progress ---------------------------------------------------------------------------------

    /**
     * Playback of [id] reached [positionTicks] (of the part). Returns the server item to mark watched
     * when this finished it: the entry of a shared-file group, or the split file once all its parts are.
     */
    fun progress(id: String, positionTicks: Long, stopped: Boolean): String? {
        watch.progress(id, positionTicks, stopped)
        return if (stopped && watch.isWatched(id)) watchedOnServer(id) else null
    }

    /** Marks [id] watched or not here; returns the server item whose watched mark should follow (or null). */
    fun setPlayed(id: String, played: Boolean): String? {
        watch.setPlayed(id, played)
        return if (played) watchedOnServer(id) else serverItemFor(id)
    }

    private fun serverItemFor(id: String): String = if (VirtualIds.isVirtual(id)) VirtualIds.serverId(id) else id

    private fun watchedOnServer(id: String): String? {
        if (!VirtualIds.isVirtual(id)) return id // a group entry is an episode of its own on the server
        val file = VirtualIds.serverId(id)
        val split = splits[file] ?: return null
        return file.takeIf { split.parts.indices.all { k -> watch.isWatched(VirtualIds.of(file, k)) } }
    }

    /** Parts in progress for this profile, most recent first (ids). */
    fun resumeIds(limit: Int): List<String> = watch.resumeIds(limit).filter(::handles)
    fun lastWatched(id: String): Long? = watch.lastWatched(id)

    // ---- Detection --------------------------------------------------------------------------------

    /** A file that may hold several episodes and hasn't been looked at yet. */
    data class Candidate(val key: String, val entries: List<BaseItemDto>, val count: Int, val reason: String)

    /**
     * Files in [items] that may hold several episodes and haven't been decided: an entry numbered as
     * several episodes (`IndexNumberEnd`) or whose file name says so (`S01E01-E02`), or several
     * entries with the same file. Needs `Path` on the items for the last two.
     */
    fun candidates(items: List<BaseItemDto>): List<Candidate> {
        val eps = items.filter { it.isEpisode && !VirtualIds.isVirtual(it.id) }
        val out = ArrayList<Candidate>()
        val grouped = HashSet<String>()
        eps.filter { !it.path.isNullOrBlank() }.groupBy { it.path!! }.values.filter { it.size >= 2 }.forEach { group ->
            val ordered = group.sortedWith(compareBy({ it.parentIndexNumber ?: 0 }, { it.indexNumber ?: Int.MAX_VALUE }))
            grouped += ordered.map { it.id }
            val key = ordered.first().id
            if (splits[key] == null) out += Candidate(key, ordered, ordered.size, "several entries share one file")
        }
        for (item in eps) {
            if (item.id in grouped || splits[item.id] != null || splits.forEntry(item.id) != null) continue
            val byNumber = item.indexNumberEnd?.let { end -> item.indexNumber?.let { end - it + 1 } }?.takeIf { it >= 2 }
            val byName = fileNameCount(item.path)
            val count = byNumber ?: byName ?: continue
            out += Candidate(item.id, listOf(item), count, if (byNumber != null) "numbered as episodes ${item.indexNumber}–${item.indexNumberEnd}" else "file name lists $count episodes")
        }
        return out
    }

    /**
     * Decides [c] from its chapters ([withChapters]: the same entries with `Chapters`): the file is
     * split only where chapters name the episodes ("Episode 2", "Part 2", or the episode's title).
     * Never by guessing (no halves). Either way the decision is kept, so a file is looked at once.
     */
    fun decide(c: Candidate, withChapters: List<BaseItemDto>): EpisodeSplit {
        val first = withChapters.firstOrNull { it.id == c.entries.first().id } ?: c.entries.first()
        val rt = first.runTimeTicks ?: 0L
        val runtimeMs = rt.ticksToMs()
        val numbers = if (c.entries.size >= 2) c.entries.map { it.indexNumber } else List(c.count) { k -> c.entries.first().indexNumber?.plus(k) }
        val titles = if (c.entries.size >= 2) c.entries.map { it.name } else splitNames(first.name, c.count) ?: List(c.count) { null }
        val starts = boundariesFromChapters(first.chapters, c.count, numbers, titles, runtimeMs)
        val split = if (starts == null) EpisodeSplit(rt, count = c.count, reason = "${c.reason}; chapters don't mark the episodes", entries = c.entries.map { it.id }.takeIf { it.size >= 2 }.orEmpty())
        else {
            val bounds = listOf(0L) + starts + runtimeMs
            EpisodeSplit(
                rt,
                parts = (0 until c.count).map { k ->
                    val thumb = chapterAt(first.chapters, bounds[k])
                    SplitPart(bounds[k], bounds[k + 1], number = numbers.getOrNull(k), entryId = c.entries.getOrNull(k)?.id?.takeIf { c.entries.size >= 2 },
                        thumbChapter = thumb?.first, thumbTag = thumb?.second)
                },
                count = c.count,
                reason = "${c.reason}; chapters name the episodes",
                entries = c.entries.map { it.id }.takeIf { it.size >= 2 }.orEmpty(),
            )
        }
        splits.put(c.key, split)
        Log.i("Splits", "${first.seriesName ?: ""} ${first.name}: ${split.reason}" + if (split.isSplit) " → split at ${split.parts.drop(1).joinToString { "${it.startMs / 1000} s" }}" else "")
        return split
    }

    companion object {
        /** A part shorter than this isn't an episode (a chapter list's "Part 2" of a single cartoon, say). */
        const val MIN_PART_MS = 3 * 60_000L

        private val EPISODE_RANGE = Regex("""[Ss]\d{1,3}\s*[Ee](\d{1,4})((?:\s*[-–_&+]\s*[Ee]?\d{1,4}|\s*[Ee]\d{1,4})+)""")
        private val CHAPTER_NUMBER = Regex("""^\s*(?:episode|ep\.?|part|segment|story|cartoon)\s*#?\s*(\d{1,4})\b""", RegexOption.IGNORE_CASE)

        /** How many episodes a file name lists (`S01E01-E02`, `S01E01E02`, `S01E01-02` → 2), or null. */
        fun fileNameCount(path: String?): Int? {
            val name = path?.substringAfterLast('/')?.substringAfterLast('\\') ?: return null
            val m = EPISODE_RANGE.find(name) ?: return null
            val first = m.groupValues[1].toInt()
            val nums = Regex("""\d{1,4}""").findAll(m.groupValues[2]).map { it.value.toInt() }.toList()
            val last = nums.lastOrNull() ?: return null
            val count = if (nums.size == 1 && last > first) last - first + 1 else nums.size + 1
            return count.takeIf { it in 2..6 }
        }

        /** "Title A / Title B" → the [count] titles, when the name is clearly a list of exactly that many. */
        fun splitNames(name: String?, count: Int): List<String>? {
            name ?: return null
            for (sep in listOf(" / ", " | ", "; ", " & ", " + ")) {
                val parts = name.split(sep).map { it.trim() }.filter { it.isNotEmpty() }
                if (parts.size == count) return parts
            }
            return null
        }

        private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

        /** The chapter starting at [ms] (within 3 s) that has an image: its index and image tag. */
        fun chapterAt(chapters: List<ChapterInfo>, ms: Long): Pair<Int, String>? =
            chapters.withIndex().firstOrNull { (_, c) -> kotlin.math.abs(c.startPositionTicks.ticksToMs() - ms) <= 3_000 && c.imageTag != null }
                ?.let { it.index to it.value.imageTag!! }

        /**
         * Where episodes 2..[count] start, from chapter names, or null if any is missing, out of order
         * or makes a part shorter than [MIN_PART_MS]. A chapter counts when it's named after the
         * episode's number ("Episode 12", "Part 2") or its title.
         */
        fun boundariesFromChapters(chapters: List<ChapterInfo>, count: Int, numbers: List<Int?>, titles: List<String?>, runtimeMs: Long): List<Long>? {
            if (count < 2 || chapters.size < count - 1) return null
            val starts = (1 until count).map { j ->
                val title = titles.getOrNull(j)?.let(::norm)?.takeIf { it.length >= 3 }
                chapters.firstOrNull { ch ->
                    val name = ch.name ?: return@firstOrNull false
                    val n = CHAPTER_NUMBER.find(name)?.groupValues?.get(1)?.toIntOrNull()
                    (n != null && (n == j + 1 || n == numbers.getOrNull(j))) || (title != null && norm(name).let { it == title || (it.length >= 6 && title.contains(it)) || it.contains(title) })
                }?.startPositionTicks?.ticksToMs() ?: return null
            }
            val bounds = listOf(0L) + starts + runtimeMs
            if (bounds.zipWithNext().any { (a, b) -> b - a < MIN_PART_MS }) return null
            return starts
        }

        /**
         * Chapter starts that could be episode starts without saying so (for the editor to suggest,
         * never applied on their own): when the file has exactly one chapter per episode.
         */
        fun suggestFromChapters(chapters: List<ChapterInfo>, count: Int, runtimeMs: Long): List<Long>? {
            if (count < 2 || chapters.size != count) return null
            val starts = chapters.map { it.startPositionTicks.ticksToMs() }.sorted()
            if (starts.first() > 5_000) return null
            val bounds = starts.drop(1)
            val all = listOf(0L) + bounds + runtimeMs
            return bounds.takeIf { all.zipWithNext().all { (a, b) -> b - a >= MIN_PART_MS } }
        }

        /** [chapters] inside [part], moved to the part's own timeline, and the index of the first one kept. */
        fun shiftChapters(chapters: List<ChapterInfo>, part: SplitPart): Pair<List<ChapterInfo>, Int> {
            if (chapters.isEmpty()) return chapters to 0
            val inside = chapters.withIndex().filter { (_, c) -> c.startPositionTicks.ticksToMs() in (part.startMs - 1_000) until (part.endMs - 1_000) }
            val base = inside.firstOrNull()?.index ?: 0
            return inside.map { (_, c) -> c.copy(startPositionTicks = (c.startPositionTicks.ticksToMs() - part.startMs).coerceAtLeast(0).msToTicks()) } to base
        }
    }
}
