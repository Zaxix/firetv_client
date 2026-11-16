package com.auroratv.playback

import com.auroratv.core.AuroraJson
import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.MediaStream
import com.auroratv.platform.FileStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * The audio and subtitle tracks chosen by hand for a series (or a movie), described by what they
 * are rather than by stream index, because indexes differ from episode to episode.
 */
@Serializable
data class TrackChoice(
    val audio: TrackTraits? = null,
    /** Subtitles explicitly turned off. */
    val subtitlesOff: Boolean = false,
    val subtitle: TrackTraits? = null,
    val updatedAt: Long = 0,
)

@Serializable
data class TrackTraits(
    val language: String? = null,
    val title: String? = null,
    val codec: String? = null,
    val forced: Boolean = false,
    val signsAndSongs: Boolean = false,
    val external: Boolean = false,
    val channels: Int? = null,
) {
    companion object {
        fun of(s: MediaStream) = TrackTraits(
            language = s.language?.lowercase(),
            title = s.title,
            codec = s.codec?.lowercase(),
            forced = s.isForced,
            signsAndSongs = TrackSelector.isSignsAndSongs(s),
            external = s.isExternal,
            channels = s.channels,
        )
    }
}

/** Result of applying a remembered choice: null fields mean "no memory, use the normal rules". */
data class RememberedTracks(val audioIndex: Int?, val subtitle: SubtitlePick?) {
    sealed interface SubtitlePick
    data object Off : SubtitlePick
    data class Track(val index: Int) : SubtitlePick
}

/**
 * Remembers manual track choices per series/movie on this device (newest 500 kept) and finds the
 * matching tracks in another episode: same language first, then the same kind (forced, signs &
 * songs, full dialogue), title, codec and channel count. If the remembered language isn't in the
 * episode, the normal preferences apply.
 */
class TrackMemory(private val files: FileStore, private val now: () -> Long) {
    private val serializer = MapSerializer(String.serializer(), TrackChoice.serializer())
    private var cache: MutableMap<String, TrackChoice>? = null
    private var file = FILE

    /** Each profile remembers its own choices; the pre-profiles file goes to the first profile opened. */
    fun switchProfile(profile: String) {
        com.auroratv.profiles.ProfileFiles.claimLegacy(files, FILE.removeSuffix(".json"), profile)
        file = com.auroratv.profiles.ProfileFiles.name(FILE.removeSuffix(".json"), profile)
        cache = null
    }

    private fun all(): MutableMap<String, TrackChoice> = cache ?: (
        files.read(file)?.let { runCatching { AuroraJson.decodeFromString(serializer, it).toMutableMap() }.getOrNull() } ?: mutableMapOf()
    ).also { cache = it }

    fun key(item: BaseItemDto): String = item.seriesId ?: item.id

    fun get(item: BaseItemDto): TrackChoice? = all()[key(item)]

    fun rememberAudio(item: BaseItemDto, stream: MediaStream) =
        update(item) { it.copy(audio = TrackTraits.of(stream)) }

    fun rememberSubtitle(item: BaseItemDto, stream: MediaStream?) =
        update(item) { if (stream == null) it.copy(subtitlesOff = true, subtitle = null) else it.copy(subtitlesOff = false, subtitle = TrackTraits.of(stream)) }

    fun forget(item: BaseItemDto) { all().remove(key(item)); persist() }

    private fun update(item: BaseItemDto, change: (TrackChoice) -> TrackChoice) {
        val map = all()
        map[key(item)] = change(map[key(item)] ?: TrackChoice()).copy(updatedAt = now())
        if (map.size > MAX) map.entries.sortedBy { it.value.updatedAt }.take(map.size - MAX).forEach { map.remove(it.key) }
        persist()
    }

    private fun persist() { runCatching { files.write(file, AuroraJson.encodeToString(serializer, all())) } }

    companion object {
        private const val FILE = "track-memory.json"
        private const val MAX = 500

        /** Finds the remembered tracks among [streams]. */
        fun apply(choice: TrackChoice?, streams: List<MediaStream>): RememberedTracks {
            if (choice == null) return RememberedTracks(null, null)
            val audio = choice.audio?.let { best(streams.filter { it.isAudio }, it) }
            val subtitle: RememberedTracks.SubtitlePick? = when {
                choice.subtitlesOff -> RememberedTracks.Off
                choice.subtitle != null -> best(streams.filter { it.isSubtitle }, choice.subtitle)?.let { RememberedTracks.Track(it) }
                else -> null
            }
            return RememberedTracks(audio, subtitle)
        }

        private fun norm(s: String?) = s.orEmpty().lowercase().filter { it.isLetterOrDigit() }

        private fun best(candidates: List<MediaStream>, want: TrackTraits): Int? {
            val sameLanguage = candidates.filter { s ->
                want.language == null || TrackSelector.languageMatches(s.language, want.language) || s.language?.lowercase() == want.language
            }
            if (sameLanguage.isEmpty()) return null
            return sameLanguage.maxByOrNull { s ->
                var score = 0
                if (s.isForced == want.forced) score += 8
                if (TrackSelector.isSignsAndSongs(s) == want.signsAndSongs) score += 8
                if (want.title != null && norm(s.title) == norm(want.title)) score += 4
                if (s.codec?.lowercase() == want.codec) score += 2
                if (s.isExternal == want.external) score += 1
                if (want.channels != null && s.channels == want.channels) score += 1
                score
            }?.index
        }
    }
}

/** Wall-clock milliseconds (for "most recently used" ordering that survives restarts). */
@OptIn(kotlin.time.ExperimentalTime::class)
fun wallClockMs(): Long = kotlin.time.Clock.System.now().toEpochMilliseconds()
