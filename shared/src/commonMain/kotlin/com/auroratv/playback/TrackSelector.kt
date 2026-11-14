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

    /**
     * True for "Signs & Songs" style tracks (anime releases): they only translate on-screen text
     * and song lyrics, not dialogue. Matches "Signs & Songs", "Signs and Songs", "Songs/Signs",
     * "S&S", "Signs", "Signs Only", "Songs Only" in the track title (but not "sign language").
     */
    fun isSignsAndSongs(stream: MediaStream): Boolean {
        val text = listOfNotNull(stream.title, stream.displayTitle).joinToString(" ")
        return text.isNotBlank() && SIGNS_SONGS.containsMatchIn(text) && !FULL_DIALOGUE.containsMatchIn(text)
    }

    /** Dialogue tracks first: not forced, not Signs & Songs. Order is otherwise kept. */
    private fun List<MediaStream>.dialogueFirst() =
        sortedBy { (if (isSignsAndSongs(it)) 2 else 0) + (if (it.isForced) 1 else 0) }

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
        // Audio already in the user's language (e.g. an English dub): only on-screen text needs translating.
        val audioIsPreferred = preferredLanguage.isNotBlank() && languageMatches(audioLanguage, preferredLanguage)

        /** Forced track, or for dubs a Signs & Songs track, which does the same job. */
        fun forcedPreferred(): Int? = (forced.firstOrNull { languageMatches(it.language, preferredLanguage) }
            ?: forced.firstOrNull { languageMatches(it.language, audioLanguage ?: "") }
            ?: inPreferred.firstOrNull { audioIsPreferred && isSignsAndSongs(it) }
            ?: forced.firstOrNull())?.index

        /** A track with the dialogue: never Signs & Songs or forced while a full track exists. */
        fun fullTrack(candidates: List<MediaStream>): MediaStream? = candidates.dialogueFirst().firstOrNull()

        return when (mode) {
            SubtitleMode.Off -> null
            SubtitleMode.OnlyForced -> forcedPreferred()
            SubtitleMode.Always -> (fullTrack(inPreferred)
                ?: fullTrack(subs.filter { it.isDefault }) ?: fullTrack(subs))?.index
            SubtitleMode.Smart -> {
                // Show full subtitles only when the audio isn't in the user's language.
                if (audioIsPreferred) forcedPreferred()
                else fullTrack(inPreferred)?.index ?: forcedPreferred()
            }
            SubtitleMode.Default -> {
                val def = subs.firstOrNull { it.isDefault && (preferredLanguage.isBlank() || languageMatches(it.language, preferredLanguage)) }
                    ?: subs.firstOrNull { it.isDefault }
                when {
                    def == null -> forcedPreferred() ?: serverDefault?.takeIf { idx -> subs.any { it.index == idx } }
                    // Release groups often flag Signs & Songs as default; with foreign audio that hides
                    // the dialogue, so use a full track in the same language instead when there is one.
                    !audioIsPreferred && (isSignsAndSongs(def) || def.isForced) ->
                        (fullTrack(subs.filter { languageMatches(it.language, def.language ?: "") }) ?: def).index
                    else -> def.index
                }
            }
        }
    }

    /** "Full Subtitles (incl. signs)", "Dialogue + Signs": complete tracks that merely mention signs. */
    private val FULL_DIALOGUE = Regex("""\b(full|dialog(ue)?|complete)\b""", RegexOption.IGNORE_CASE)

    private val SIGNS_SONGS = Regex(
        """\bsigns?\s*(?:&|and|\+|/|,|-)?\s*songs?\b|\bsongs?\s*(?:&|and|\+|/|,|-)?\s*signs?\b|\bs\s*&\s*s\b|\bsigns?\b(?!\s*language)|\bsongs?\s+only\b""",
        RegexOption.IGNORE_CASE,
    )

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
