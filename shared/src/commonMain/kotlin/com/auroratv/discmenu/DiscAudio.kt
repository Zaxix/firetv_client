package com.auroratv.discmenu

import com.auroratv.jellyfin.model.MediaStream

/**
 * Maps the Languages page's spoken-language actions (`audio:<code>`) onto the file's audio tracks.
 * Codes: `en` (English), `es` (Spanish), `fr` (French). The track is the film's own, found by its language tag;
 * nothing is bundled with the menu.
 */
object DiscAudio {
    private val language = mapOf(
        "en" to setOf("eng", "en"),
        "es" to setOf("spa", "es"),
        "fr" to setOf("fra", "fre", "fr"),
    )
    private val notDialogue = Regex("""\b(commentary|descri|audio description|karaoke)""", RegexOption.IGNORE_CASE)
    private val quebec = Regex("""qu[eé]b|canad|\bfr[-_ ]?ca\b|\bcf\b""", RegexOption.IGNORE_CASE)

    private fun text(s: MediaStream) = s.title.orEmpty() + " " + s.displayTitle.orEmpty()

    /** The audio track for [code] among [streams], or null when this file has none. Surround is preferred; for French the Québec dub the disc names. */
    fun find(streams: List<MediaStream>, code: String): MediaStream? {
        val langs = language[code] ?: return null
        val tracks = streams.filter { it.isAudio && it.language?.lowercase() in langs && !notDialogue.containsMatchIn(text(it)) }
        if (tracks.isEmpty()) return null
        return tracks.sortedWith(
            compareByDescending<MediaStream> { code == "fr" && quebec.containsMatchIn(text(it)) }
                .thenByDescending { (it.channels ?: 0) >= 6 }
                .thenByDescending { it.isDefault },
        ).first()
    }

    fun isAvailable(streams: List<MediaStream>, code: String): Boolean = find(streams, code) != null

    /** The audio stream index to hand to the player, or null (no opinion) for an unknown code or a track the file doesn't have. */
    fun override(code: String?): ((List<MediaStream>) -> Int?)? {
        if (code == null) return null
        return { streams -> find(streams, code)?.index }
    }

    fun label(code: String): String = when (code) {
        "en" -> "Audio: English"
        "es" -> "Audio: Español"
        "fr" -> "Audio: Français"
        else -> "Audio: $code"
    }
}
