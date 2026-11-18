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

    /**
     * What a subtitle track holds, worked out from its forced and hearing-impaired flags and its
     * title (release groups name tracks far more reliably than they flag them). The order is the
     * order of preference when the viewer needs the dialogue.
     */
    enum class SubtitleKind { Full, HearingImpaired, Other, Signs }

    fun subtitleKind(stream: MediaStream): SubtitleKind {
        val text = listOfNotNull(stream.title, stream.displayTitle).joinToString(" ")
        val saysFull = FULL_DIALOGUE.containsMatchIn(text)
        return when {
            isSignsAndSongs(stream) -> SubtitleKind.Signs
            // A track called "Full" that's flagged forced is a mislabelled full track, not signs.
            (stream.isForced || FORCED_TITLE.containsMatchIn(text)) && !saysFull -> SubtitleKind.Signs
            stream.isHearingImpaired || HEARING_IMPAIRED.containsMatchIn(text) -> SubtitleKind.HearingImpaired
            COMMENTARY.containsMatchIn(text) -> SubtitleKind.Other
            else -> SubtitleKind.Full
        }
    }

    /** No language tag, or ISO's "undetermined" codes. */
    fun isUnknownLanguage(lang: String?): Boolean = lang.isNullOrBlank() || normalize(lang) in UNKNOWN_LANGUAGES

    /**
     * The track with the dialogue among [candidates]: full, then SDH/CC, then anything else, and
     * Signs & Songs / forced only if nothing else exists. Ties go to the file's default flag, then
     * the server's default, then the file's own order.
     */
    private fun dialogueTrack(candidates: List<MediaStream>, serverDefault: Int?): MediaStream? =
        candidates.sortedWith(compareBy<MediaStream>({ subtitleKind(it).ordinal }, { !it.isDefault }, { it.index != serverDefault })).firstOrNull()

    /** A signs-only track (forced flag first) among [candidates]. */
    private fun signsTrack(candidates: List<MediaStream>): MediaStream? =
        candidates.filter { subtitleKind(it) == SubtitleKind.Signs }.sortedWith(compareBy<MediaStream>({ !it.isForced }, { !it.isDefault })).firstOrNull()

    /**
     * Returns the subtitle stream index to show, or null for none. A track chosen by hand earlier is
     * applied before this (see [TrackMemory]); this is the automatic choice. A blank
     * [preferredLanguage] means English, like the automatic audio language.
     *
     * When the viewer needs the dialogue (the audio isn't in their language), the order is: full
     * track, SDH/CC, other tracks, and Signs & Songs / forced only when it's all there is. Signs &
     * Songs is chosen on purpose only when the audio is already in their language (a dub), where it
     * translates just the on-screen text, or in "Only forced" mode.
     */
    fun pickSubtitle(
        streams: List<MediaStream>,
        mode: SubtitleMode,
        preferredLanguage: String,
        audioLanguage: String?,
        serverDefault: Int?,
    ): Int? {
        val subs = streams.filter { it.isSubtitle }
        if (subs.isEmpty() || mode == SubtitleMode.Off) return null
        val target = preferredLanguage.ifBlank { DEFAULT_LANGUAGE }
        val validServerDefault = serverDefault?.takeIf { idx -> subs.any { it.index == idx } }
        // Audio already in the viewer's language (e.g. an English dub): only on-screen text needs translating.
        val audioInTarget = languageMatches(audioLanguage, target)
        val inTarget = subs.filter { languageMatches(it.language, target) }
        val flaggedDefault = subs.firstOrNull { it.isDefault && languageMatches(it.language, target) } ?: subs.firstOrNull { it.isDefault }
        // Where to look for the dialogue: the viewer's language, else the language the file itself
        // flags as default, else untagged tracks (often the only kind on older files).
        val candidates = inTarget
            .ifEmpty { flaggedDefault?.language?.let { l -> subs.filter { languageMatches(it.language, l) || it.language == l } }.orEmpty() }
            .ifEmpty { subs.filter { isUnknownLanguage(it.language) } }

        fun signs(): MediaStream? = signsTrack(inTarget)
            ?: signsTrack(subs.filter { languageMatches(it.language, audioLanguage ?: "") })
            ?: (if (inTarget.isEmpty()) signsTrack(subs.filter { isUnknownLanguage(it.language) }) else null)

        return when (mode) {
            SubtitleMode.Off -> null
            SubtitleMode.OnlyForced -> (signs() ?: subs.firstOrNull { it.isForced })?.index
            SubtitleMode.Always -> (dialogueTrack(candidates, validServerDefault) ?: dialogueTrack(subs, validServerDefault))?.index
            SubtitleMode.Smart ->
                // Full subtitles only when the audio isn't in the viewer's language.
                if (audioInTarget) signs()?.index
                else (dialogueTrack(candidates, validServerDefault) ?: signs())?.index
            SubtitleMode.Default -> {
                // "As the file says": subtitles are shown when a track is flagged default or forced (or
                // the server names one), and also whenever the audio is in another language and there's
                // a track in the viewer's language. Which track is our choice, not the flag's: release
                // groups often flag Signs & Songs default (or forced), which hides the dialogue.
                val fileWantsSubs = flaggedDefault != null || subs.any { it.isForced } || validServerDefault != null
                val foreignAudio = !audioInTarget && !isUnknownLanguage(audioLanguage)
                when {
                    audioInTarget -> (flaggedDefault ?: signs())?.index ?: validServerDefault
                    foreignAudio && inTarget.isNotEmpty() -> dialogueTrack(inTarget, validServerDefault)?.index
                    fileWantsSubs -> (dialogueTrack(candidates, validServerDefault)
                        ?: dialogueTrack(subs.filter { it.isDefault || it.isForced || it.index == validServerDefault }, validServerDefault))?.index
                    else -> null
                }
            }
        }
    }

    /** "Full Subtitles (incl. signs)", "Dialogue + Signs": complete tracks that merely mention signs. */
    private val FULL_DIALOGUE = Regex("""\b(full|dialog(ue)?|complete)\b""", RegexOption.IGNORE_CASE)

    /** "Forced", "Forced Subtitles", "Foreign Parts Only". */
    private val FORCED_TITLE = Regex("""\bforced\b|\bforeign\s+(parts?|only)\b|\bnon[- ]?english\s+parts?\b""", RegexOption.IGNORE_CASE)

    private val HEARING_IMPAIRED = Regex("""\b(sdh|cc|hoh|hearing[ -]?impaired|closed[ -]?captions?)\b|[\[(]hi[\])]""", RegexOption.IGNORE_CASE)

    private val COMMENTARY = Regex("""\bcommentary\b""", RegexOption.IGNORE_CASE)

    private val UNKNOWN_LANGUAGES = setOf("und", "unk", "mis", "zxx", "mul")

    /** What a blank subtitle language means: English, like the automatic audio language. */
    const val DEFAULT_LANGUAGE = "eng"

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
