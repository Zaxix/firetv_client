package com.auroratv.playback

import com.auroratv.jellyfin.model.MediaStream
import com.auroratv.settings.SubtitleMode

/** Chooses initial audio/subtitle streams from user preferences, mirroring Jellyfin's own rules. */
object TrackSelector {

    fun languageMatches(streamLang: String?, preferred: String): Boolean {
        if (preferred.isBlank() || streamLang.isNullOrBlank()) return false
        val a = normalize(streamLang)
        val b = normalize(preferred)
        return a == b
    }

    /** Maps ISO 639-1 to 639-2 so "en" matches Jellyfin's "eng". */
    fun normalize(lang: String): String {
        val l = lang.lowercase().trim().substringBefore('-').substringBefore('_')
        return ISO1_TO_2[l] ?: when (l) { "ger" -> "deu"; "fre" -> "fra"; "dut" -> "nld"; "chi" -> "zho"; "cze" -> "ces"; "gre" -> "ell"; "per" -> "fas"; "rum" -> "ron"; "slo" -> "slk"; else -> l }
    }

    fun pickAudio(streams: List<MediaStream>, preferredLanguage: String, serverDefault: Int?): Int? {
        val audio = streams.filter { it.isAudio }
        if (audio.isEmpty()) return null
        audio.firstOrNull { languageMatches(it.language, preferredLanguage) && it.isDefault }?.let { return it.index }
        audio.firstOrNull { languageMatches(it.language, preferredLanguage) }?.let { return it.index }
        serverDefault?.takeIf { idx -> audio.any { it.index == idx } }?.let { return it }
        return (audio.firstOrNull { it.isDefault } ?: audio.first()).index
    }

    /** Returns the subtitle stream index to show, or null for none. */
    fun pickSubtitle(
        streams: List<MediaStream>,
        mode: SubtitleMode,
        preferredLanguage: String,
        audioLanguage: String?,
        serverDefault: Int?,
    ): Int? {
        val subs = streams.filter { it.isSubtitle }
        if (subs.isEmpty() || mode == SubtitleMode.Off) return null
        val inPreferred = subs.filter { languageMatches(it.language, preferredLanguage) }
        val forced = subs.filter { it.isForced }
        fun forcedPreferred() = (forced.firstOrNull { languageMatches(it.language, preferredLanguage) }
            ?: forced.firstOrNull { languageMatches(it.language, audioLanguage ?: "") }
            ?: forced.firstOrNull())?.index

        return when (mode) {
            SubtitleMode.Off -> null
            SubtitleMode.OnlyForced -> forcedPreferred()
            SubtitleMode.Always -> (inPreferred.firstOrNull { !it.isForced } ?: inPreferred.firstOrNull()
                ?: subs.firstOrNull { it.isDefault } ?: subs.first()).index
            SubtitleMode.Smart -> {
                // Show full subtitles only when the audio isn't in the user's language.
                val audioIsPreferred = preferredLanguage.isNotBlank() && languageMatches(audioLanguage, preferredLanguage)
                if (audioIsPreferred) forcedPreferred()
                else (inPreferred.firstOrNull { !it.isForced } ?: inPreferred.firstOrNull())?.index ?: forcedPreferred()
            }
            SubtitleMode.Default -> {
                val def = subs.firstOrNull { it.isDefault && (preferredLanguage.isBlank() || languageMatches(it.language, preferredLanguage)) }
                    ?: subs.firstOrNull { it.isDefault }
                def?.index ?: forcedPreferred() ?: serverDefault?.takeIf { idx -> subs.any { it.index == idx } }
            }
        }
    }

    private val ISO1_TO_2 = mapOf(
        "en" to "eng", "fr" to "fra", "de" to "deu", "es" to "spa", "it" to "ita", "ja" to "jpn", "ko" to "kor",
        "zh" to "zho", "pt" to "por", "ru" to "rus", "nl" to "nld", "sv" to "swe", "no" to "nor", "nb" to "nob",
        "da" to "dan", "fi" to "fin", "pl" to "pol", "cs" to "ces", "hu" to "hun", "tr" to "tur", "ar" to "ara",
        "he" to "heb", "hi" to "hin", "th" to "tha", "vi" to "vie", "id" to "ind", "el" to "ell", "ro" to "ron",
        "uk" to "ukr", "fa" to "fas", "ms" to "msa", "bg" to "bul", "hr" to "hrv", "sk" to "slk", "sl" to "slv",
        "sr" to "srp", "ca" to "cat", "et" to "est", "lv" to "lav", "lt" to "lit", "is" to "isl", "ga" to "gle",
    )

    /** A readable label like "English · AC3 5.1 · Default". */
    fun label(stream: MediaStream): String {
        val parts = mutableListOf<String>()
        parts += stream.displayTitle ?: stream.title ?: stream.language?.uppercase() ?: "Track ${stream.index}"
        if (stream.isExternal) parts += "External"
        return parts.joinToString(" · ")
    }
}
