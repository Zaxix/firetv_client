package com.auroratv.discmenu

import com.auroratv.jellyfin.model.MediaStream
import com.auroratv.playback.SubtitleOverride

/**
 * Maps the Languages page's subtitle actions (`subtitle:<code>`) onto the file's subtitle tracks.
 * Codes: `off`, `en` (English), `en_hi` (English for the hearing impaired), `ar` (Arabic), `es` (Spanish), `fr` (French).
 */
object DiscSubtitles {
    private val language = mapOf("en" to setOf("eng", "en"), "en_hi" to setOf("eng", "en"), "ar" to setOf("ara", "ar"),
        "es" to setOf("spa", "es"), "fr" to setOf("fra", "fre", "fr"),
    )
    private val hearingImpairedWords = Regex("""\b(sdh|hi|hearing|cc|closed caption)""", RegexOption.IGNORE_CASE)

    private fun isHearingImpaired(s: MediaStream): Boolean =
        s.isHearingImpaired || hearingImpairedWords.containsMatchIn(s.title.orEmpty() + " " + s.displayTitle.orEmpty())

    /** The subtitle track for [code] among [streams], or null when this file has none. */
    fun find(streams: List<MediaStream>, code: String): MediaStream? {
        val langs = language[code] ?: return null
        val subs = streams.filter { it.type == "Subtitle" && !it.isForced && it.language?.lowercase() in langs }
        return when (code) {
            "en_hi" -> subs.firstOrNull(::isHearingImpaired)
            "en" -> subs.firstOrNull { !isHearingImpaired(it) } ?: subs.firstOrNull()
            else -> subs.firstOrNull()
        }
    }

    fun isAvailable(streams: List<MediaStream>, code: String): Boolean = code == "off" || find(streams, code) != null

    /** The override to hand to the player, or null (no opinion) for an unknown code or a track the file doesn't have. */
    fun override(code: String?): ((List<MediaStream>) -> SubtitleOverride?)? {
        if (code == null) return null
        return { streams ->
            if (code == "off") SubtitleOverride.Off else find(streams, code)?.let { SubtitleOverride.Track(it.index) }
        }
    }

    fun label(code: String): String = when (code) {
        "off" -> "Subtitles off"
        "en" -> "Subtitles: English"
        "en_hi" -> "Subtitles: English for the Hearing Impaired"
        "ar" -> "Subtitles: Arabic"
        "es" -> "Subtitles: Español"
        "fr" -> "Subtitles: Français"
        else -> "Subtitles: $code"
    }
}
