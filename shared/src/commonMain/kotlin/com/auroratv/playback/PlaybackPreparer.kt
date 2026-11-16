package com.auroratv.playback

import com.auroratv.core.Log
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.MediaSourceInfo
import com.auroratv.settings.ResumeBehavior
import com.auroratv.settings.Settings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.coroutineScope
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Everything the player needs before it can hand a stream to the engine. */
class PreparedPlayback(
    val item: BaseItemDto,
    val plan: PlaybackPlan,
    val startMs: Long,
    val audioIndex: Int?,
    val subtitleIndex: Int?,
    /** MKV font attachments for ASS/SSA when the server transcodes (empty for Direct Play). */
    val fonts: List<SubtitleFont>,
)

/**
 * The work between pressing Play and starting the engine: item + track choice, PlaybackInfo, and
 * (for transcodes with ASS subtitles) the MKV font attachments. Font downloads start alongside the
 * PlaybackInfo request instead of after it, and are dropped as soon as the server picks Direct Play.
 */
object PlaybackPreparer {
    private const val TAG = "Prepare"
    private const val MAX_FONT_BYTES = 64L * 1024 * 1024
    private const val FONT_DOWNLOADS_AT_ONCE = 6

    suspend fun prepare(
        api: JellyfinApi,
        negotiator: PlaybackNegotiator,
        settings: Settings,
        itemId: String,
        startFromBeginning: Boolean,
        knownItem: BaseItemDto? = null,
        trackMemory: TrackMemory? = null,
    ): PreparedPlayback = coroutineScope {
        val t0 = TimeSource.Monotonic.markNow()
        // Ask how to play the file while the item details are still on their way (one round trip
        // instead of two). Direct Play doesn't depend on the start point, and the player picks the
        // tracks itself, so that answer is kept when it fits the tracks chosen below; anything else
        // (a transcode, a track that needs the server) is negotiated again with the real choices.
        val early = if (knownItem == null && ResumeTuning.parallelPrepare) async {
            try {
                negotiator.negotiate(itemId, null, 0L, null, null, settings.maxBitrateMbps, settings.enableDirectPlay)
            } catch (c: CancellationException) { throw c } catch (t: Throwable) { null }
        } else null
        val item = knownItem ?: api.item(itemId)
        val itemMs = t0.elapsedNow().inWholeMilliseconds
        val source = item.mediaSources.firstOrNull()
        val streams = source?.mediaStreams ?: item.mediaStreams
        // Tracks picked by hand earlier in this series (or movie) win over the general preferences.
        val remembered = TrackMemory.apply(trackMemory?.takeIf { settings.rememberTracks }?.get(item), streams)
        val audio = remembered.audioIndex ?: TrackSelector.pickAudio(streams, settings.preferredAudioLanguage, source?.defaultAudioStreamIndex)
        val audioLang = streams.firstOrNull { it.index == audio }?.language
        val subtitle = when (val pick = remembered.subtitle) {
            RememberedTracks.Off -> null
            is RememberedTracks.Track -> pick.index
            null -> TrackSelector.pickSubtitle(streams, settings.subtitleMode, settings.preferredSubtitleLanguage, audioLang, source?.defaultSubtitleStreamIndex)
        }
        val startMs = if (startFromBeginning || settings.resumeBehavior == ResumeBehavior.StartOver) 0L else item.resumePositionMs
        val fonts = source?.takeIf { needsFonts(it) }?.let { s -> async { downloadFonts(api, itemId, s) } }
        val earlyPlan = early?.await()?.takeIf { p ->
            (source == null || p.mediaSource.id == source.id) && negotiator.directPlayFits(p, audio, subtitle)
        }
        val plan = earlyPlan?.copy(audioIndex = audio, subtitleIndex = subtitle)
            ?: negotiator.negotiate(itemId, source?.id, startMs, audio, subtitle, settings.maxBitrateMbps, settings.enableDirectPlay)
        Log.i("Perf", "Prepared in ${t0.elapsedNow().inWholeMilliseconds} ms (item ${if (knownItem != null) "known" else "$itemMs ms"}, " +
            "${if (earlyPlan != null) "PlaybackInfo alongside it" else if (early != null) "PlaybackInfo again for this start/tracks" else "PlaybackInfo after it"}, ${plan.method.jellyfinName})")
        val loadedFonts = when {
            fonts == null -> emptyList()
            plan.method == PlayMethod.DirectPlay || !hasAss(plan.subtitleStreams) -> { fonts.cancel(); emptyList() }
            else -> fonts.await()
        }
        PreparedPlayback(item, plan, startMs, audio, subtitle, loadedFonts)
    }

    private fun hasAss(streams: List<com.auroratv.jellyfin.model.MediaStream>) = streams.any { (it.codec ?: "").lowercase() in setOf("ass", "ssa") }

    fun needsFonts(source: MediaSourceInfo) = source.mediaAttachments.any { it.isFont } && hasAss(source.mediaStreams.filter { it.isSubtitle })

    /** Downloads font attachments (capped at 64 MB and 20 s); failures just leave a font out. */
    suspend fun downloadFonts(api: JellyfinApi, itemId: String, source: MediaSourceInfo): List<SubtitleFont> {
        val attachments = source.mediaAttachments.filter { it.isFont }
        if (attachments.isEmpty()) return emptyList()
        var total = 0L
        // Real-time budget (Default dispatcher), independent of the caller's clock.
        // Several at a time (anime releases often carry 10–30 fonts): far quicker than one by one.
        val parallel = kotlinx.coroutines.sync.Semaphore(FONT_DOWNLOADS_AT_ONCE)
        // One thread for the bookkeeping (the downloads themselves still overlap), so the size cap is exact.
        val loaded = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)) { kotlinx.coroutines.withTimeoutOrNull(20_000) {
            coroutineScope {
                attachments.map { a ->
                    async {
                        parallel.withPermit {
                            if (total > MAX_FONT_BYTES) return@withPermit null
                            val url = a.deliveryUrl ?: "/Videos/$itemId/${source.id}/Attachments/${a.index}"
                            runCatching { api.downloadBytes(url) }
                                .onFailure { if (it is CancellationException) throw it; Log.w(TAG, "Font attachment ${a.index} failed: ${it.message}") }
                                .getOrNull()?.let { bytes -> total += bytes.size; SubtitleFont(a.fileName ?: "font-${a.index}.ttf", bytes) }
                        }
                    }
                }.awaitAll().filterNotNull()
            }
        } }.orEmpty()
        Log.i(TAG, "Loaded ${loaded.size}/${attachments.size} font attachments (${total / 1024} KB) for ASS subtitles")
        return loaded
    }
}

/**
 * Prepares the title a detail page's Play button would start while the viewer is still reading the
 * page, so pressing Play goes straight to the engine. One title at a time; a result is used once,
 * only if it is under 90 seconds old and was made with the same server session, settings and device
 * capabilities. Call from the main thread.
 */
class PlaybackPrewarmer(private val scope: CoroutineScope, private val trackMemory: TrackMemory? = null) {
    private class Entry(
        val itemId: String,
        val api: JellyfinApi,
        val settings: Settings,
        val caps: DeviceCapabilities,
        val job: Deferred<PreparedPlayback?>,
        val created: TimeMark,
    )

    private var entry: Entry? = null

    fun prewarm(api: JellyfinApi, caps: DeviceCapabilities, settings: Settings, itemId: String) {
        val e = entry
        if (e != null && e.itemId == itemId && e.api === api && e.settings == settings && e.caps == caps && e.created.elapsedNow() < MAX_AGE / 2) return
        e?.job?.cancel()
        val job = scope.async {
            try {
                PlaybackPreparer.prepare(api, PlaybackNegotiator(api, caps), settings, itemId, startFromBeginning = false, trackMemory = trackMemory)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                Log.w("Prewarm", "Couldn't prepare $itemId ahead of time: ${t.message}"); null
            }
        }
        entry = Entry(itemId, api, settings, caps, job, TimeSource.Monotonic.markNow())
    }

    /**
     * The prepared playback for [itemId], waiting for it if it is still in flight; null when there
     * is none that matches. Consumes the entry either way.
     */
    suspend fun take(api: JellyfinApi, caps: DeviceCapabilities, settings: Settings, itemId: String): PreparedPlayback? {
        val e = entry ?: return null
        entry = null
        val usable = e.itemId == itemId && e.api === api && e.settings == settings && e.caps == caps && e.created.elapsedNow() < MAX_AGE
        if (!usable || e.job.isCancelled) { e.job.cancel(); return null }
        return e.job.await()
    }

    /** Drops any prepared result (e.g. after the item's watched state or resume point changed). */
    fun invalidate(itemId: String? = null) {
        val e = entry ?: return
        if (itemId == null || e.itemId == itemId) { e.job.cancel(); entry = null }
    }

    private companion object { val MAX_AGE = 90.seconds }
}
