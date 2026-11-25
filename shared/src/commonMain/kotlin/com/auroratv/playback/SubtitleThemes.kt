package com.auroratv.playback

import com.auroratv.core.Log
import com.auroratv.jellyfin.model.BaseItemDto
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A font for plain text subtitles: its family name and files (regular, and italic when there is one). */
class SubtitleTextFont(val family: String, val files: List<SubtitleFont>, /** Size relative to the usual subtitle font (some faces run small). */ val scale: Float = 1f)

/**
 * A font that suits a film, for its normal (text) subtitles: SRT, WebVTT and the like. Styled
 * ASS/SSA subtitles keep their own fonts, and picture subtitles (PGS, VobSub) can't change.
 * Every font here is openly licensed (SIL OFL, licence in LICENSES/) and bundled in the app, so
 * nothing is downloaded and the server isn't involved.
 */
data class SubtitleTheme(
    val id: String,
    /** Shown in the player's info panel and Settings. */
    val label: String,
    val family: String,
    val regular: String,
    val italic: String? = null,
    val scale: Float = 1f,
    /** Which titles use it. */
    val matches: (BaseItemDto) -> Boolean,
)

object SubtitleThemes {
    /**
     * The Harry Potter films: IM Fell English (Igino Marini, OFL), an old-style printed face with
     * a storybook feel that stays readable at subtitle size. Not the films' own lettering (that's a
     * trademark, and the copies online aren't licensed to bundle). Matched by the films' TMDB
     * collection, or a title "Harry Potter and the …".
     */
    val Wizarding = SubtitleTheme(
        id = "wizarding",
        label = "IM Fell English (Harry Potter films)",
        family = "IM FELL English",
        regular = "files/subtitle-fonts/IMFellEnglish-Regular.ttf",
        italic = "files/subtitle-fonts/IMFellEnglish-Italic.ttf",
        // A small x-height for its size: a touch larger reads like the usual subtitle font.
        scale = 1.1f,
        matches = { item -> item.isMovie && (item.providerIds.entries.any { (k, v) -> k.equals("TmdbCollection", true) && v == HARRY_POTTER_TMDB_COLLECTION } || HARRY_POTTER_TITLE.containsMatchIn(item.name.orEmpty()) || HARRY_POTTER_TITLE.containsMatchIn(item.originalTitle.orEmpty())) },
    )

    /** Only the Harry Potter films for now; more themes can be added to this list. */
    val all: List<SubtitleTheme> = listOf(Wizarding)

    /** TMDB's "Harry Potter Collection" (the eight films). */
    const val HARRY_POTTER_TMDB_COLLECTION = "1241"
    private val HARRY_POTTER_TITLE = Regex("""^\s*Harry Potter and the\b""", RegexOption.IGNORE_CASE)

    fun forItem(item: BaseItemDto?): SubtitleTheme? = item?.let { i -> all.firstOrNull { it.matches(i) } }

    private val loaded = HashMap<String, SubtitleTextFont>()
    private val lock = Mutex()

    /**
     * The font files for [theme], read from the app's own resources once per run (≈200 KB each) and
     * kept for the next film. Null if they can't be read (the usual font is used then).
     */
    suspend fun font(theme: SubtitleTheme, read: suspend (String) -> ByteArray): SubtitleTextFont? = lock.withLock {
        loaded[theme.id]?.let { return@withLock it }
        runCatching {
            val files = listOfNotNull(theme.regular, theme.italic).map { path -> SubtitleFont(path.substringAfterLast('/'), read(path)) }
            SubtitleTextFont(theme.family, files, theme.scale).also { loaded[theme.id] = it }
        }.onFailure { Log.w("Subtitles", "Couldn't read the ${theme.label} font: ${it.message}") }.getOrNull()
    }
}
