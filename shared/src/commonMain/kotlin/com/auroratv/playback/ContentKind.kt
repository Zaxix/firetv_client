package com.auroratv.playback

import com.auroratv.jellyfin.model.BaseItemDto

/**
 * What kind of production a movie or series is, from the metadata the server already has: Japanese
 * anime, other animation (Western cartoons, CGI films), or live action. Used to pick the default
 * audio language and to keep recommendations within the same kind of content.
 *
 * Anime when an anime database id is present (AniList/AniDB/MyAnimeList/Kitsu plugins), or "Anime"
 * is a genre or tag, or it's animated *and* Japanese (made in Japan, a Japanese original title, or
 * Japanese audio). Being animated alone never makes something anime.
 */
enum class ContentKind {
    Anime, Animation, LiveAction;

    companion object {
        private val ANIME_IDS = setOf("anilist", "anidb", "myanimelist", "mal", "kitsu")

        /** [audioLanguages]: the file's audio track languages when known (episodes carry no genres). */
        fun of(item: BaseItemDto, audioLanguages: Collection<String?> = emptyList()): ContentKind {
            if (item.providerIds.keys.any { it.lowercase() in ANIME_IDS }) return Anime
            val words = (item.genres + item.tags).map { it.lowercase().trim() }
            if (words.any { it == "anime" || it.startsWith("anime ") }) return Anime
            val animated = words.any { it == "animation" || it == "animated" || it == "cartoon" || it == "adult animation" }
            if (!animated) return LiveAction
            val japanese = item.productionLocations.any { it.equals("Japan", ignoreCase = true) } ||
                hasJapaneseScript(item.originalTitle) ||
                audioLanguages.any { TrackSelector.normalize(it ?: "") == "jpn" }
            return if (japanese) Anime else Animation
        }

        /** Kana or kanji anywhere in [text] (an anime's original title on TMDB/TVDB is usually Japanese). */
        fun hasJapaneseScript(text: String?): Boolean = text?.any { c ->
            c.code in 0x3040..0x30FF || c.code in 0x4E00..0x9FFF || c.code in 0x31F0..0x31FF
        } == true
    }
}
