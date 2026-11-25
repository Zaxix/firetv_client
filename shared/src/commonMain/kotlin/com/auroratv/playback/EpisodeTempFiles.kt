package com.auroratv.playback

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.platform.FileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * A cache of app-made copies that can always be fetched again (Android: the saved starts of
 * font-heavy MKVs, see MediaHeaderCache). Nothing in it is original media, subtitles, metadata,
 * watch history or settings.
 */
interface DisposableCache {
    /** Every key it holds, from its own index (no disk scan). */
    fun keys(): Set<String>
    /** Deletes one entry; a missing one is fine. */
    fun remove(key: String)
}

/**
 * Lifecycle of an episode's temporary files: what decides when a saved file start is deleted.
 *
 * Each cache entry is "held" by the profiles that may still need it, each hold with an expiry:
 * - fetched ahead (details page / Continue Watching) or played and left unfinished: held for
 *   [KEEP_MS] (5 days) from then; resuming and leaving again starts the 5 days over;
 * - finished (90% or more, like the server's "watched"): that profile's hold goes at once;
 * - while it plays it's never deleted, whatever its holds say.
 * An entry is deleted when nobody holds it any more. Other profiles' holds, and the file being
 * played right now, always protect it.
 *
 * Event-driven: work happens when playback starts or stops and once per app start ([sweep]), never
 * on a timer. The only thing stored is one small JSON map (key → profile → expiry), written only
 * when it changes. Crash-safe: the file is deleted before its record, so an interrupted cleanup
 * leaves at most a record for a missing file (dropped by the next sweep) or a file without a record
 * (adopted by the next sweep and kept for another 5 days).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodeTempFiles(
    private val files: FileStore,
    private val cache: DisposableCache?,
    private val scope: CoroutineScope,
    private val now: () -> Long = { wallClockMs() },
    /** One worker, so the map is only ever touched from one place at a time. */
    private val serial: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    @Serializable
    private data class Entry(val item: String, val holds: Map<String, Long>)

    private var map: MutableMap<String, Entry>? = null
    private val playing = HashMap<String, Int>()
    private val serializer = MapSerializer(String.serializer(), Entry.serializer())

    /** The start of [key] was fetched ahead for [profile] (it may never be played). */
    fun fetchedAhead(key: String, itemId: String, profile: String) = work { hold(key, itemId, profile) }

    /** Playback of [key] began: protected until [stopped]. */
    fun started(key: String, itemId: String, profile: String) = work {
        playing[key] = (playing[key] ?: 0) + 1
        hold(key, itemId, profile)
    }

    /** Playback of [key] ended; [finished]: watched to the end (the episode won't be resumed). */
    fun stopped(key: String, profile: String, finished: Boolean) = work {
        playing[key]?.let { n -> if (n <= 1) playing.remove(key) else playing[key] = n - 1 }
        val m = load()
        val e = m[key] ?: return@work
        val holds = if (finished) e.holds - profile else e.holds + (profile to now() + KEEP_MS)
        if (holds.isEmpty()) delete(key, if (finished) "finished" else "unused") else { m[key] = e.copy(holds = holds); save() }
    }

    /** A profile was removed from the device: it no longer holds anything. */
    fun profileRemoved(profile: String) = work {
        val m = load()
        val touched = m.filterValues { profile in it.holds }.keys
        if (touched.isEmpty()) return@work
        touched.forEach { k -> m[k] = m.getValue(k).let { it.copy(holds = it.holds - profile) } }
        save()
        collect()
    }

    /**
     * Once per app start: expired holds go, files nobody holds are deleted, records of files that are
     * gone are dropped, and files without a record (from before this ledger, or an interrupted run)
     * are adopted with a fresh 5 days.
     */
    fun sweep() = work {
        val m = load()
        val present = runCatching { cache?.keys() }.getOrNull() ?: return@work
        var changed = false
        (m.keys - present).forEach { m.remove(it); changed = true }
        (present - m.keys).forEach { m[it] = Entry("", mapOf(ADOPTED to now() + KEEP_MS)); changed = true }
        if (changed) save()
        collect()
    }

    /** For tests and diagnostics: keys with their holders. */
    internal fun snapshot(): Map<String, Set<String>> = load().mapValues { it.value.holds.keys }

    private fun hold(key: String, itemId: String, profile: String) {
        val m = load()
        val e = m[key]
        val until = now() + KEEP_MS
        if (e != null && (e.holds[profile] ?: 0) >= until - SAVE_SLACK_MS) return // just renewed: skip the write
        m[key] = Entry(itemId.ifEmpty { e?.item.orEmpty() }, (e?.holds.orEmpty() - ADOPTED) + (profile to until))
        save()
    }

    /** Drops expired holds and deletes every entry nobody holds (except what's playing). */
    private fun collect() {
        val m = load()
        val t = now()
        var changed = false
        for (key in m.keys.toList()) {
            val e = m.getValue(key)
            val live = e.holds.filterValues { it > t }
            if (live.size != e.holds.size) { m[key] = e.copy(holds = live); changed = true }
            if (live.isEmpty()) delete(key, "not resumed within 5 days")
        }
        if (changed) save()
    }

    private fun delete(key: String, why: String) {
        if (key in playing) return
        val m = load()
        // The file first, then its record: an interruption never loses track of a file.
        if (runCatching { cache?.remove(key) }.isFailure) return
        m.remove(key)
        save()
        Log.i(TAG, "Deleted a saved file start ($why)")
    }

    private fun load(): MutableMap<String, Entry> = map ?: (runCatching {
        files.read(FILE)?.let { AuroraJson.decodeFromString(serializer, it).toMutableMap() }
    }.getOrNull() ?: mutableMapOf()).also { map = it }

    private fun save() {
        val m = map ?: return
        runCatching { if (m.isEmpty()) files.delete(FILE) else files.write(FILE, AuroraJson.encodeToString(serializer, m)) }
    }

    private fun work(block: () -> Unit) {
        scope.launch(serial) { runCatching(block).onFailure { Log.w(TAG, "Cleanup step failed: ${it.message}") } }
    }

    companion object {
        /** How long an unfinished episode's files are kept after it was last left (5 days). */
        const val KEEP_MS = 5L * 24 * 60 * 60 * 1000
        /** Renewing a hold within this long of the last renewal isn't worth a write. */
        private const val SAVE_SLACK_MS = 60L * 60 * 1000
        private const val ADOPTED = "?"
        private const val FILE = "temp-files.json"
        private const val TAG = "TempFiles"
    }
}
