package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.MediaStream
import com.auroratv.playback.ContentKind
import com.auroratv.playback.PlaybackPreparer
import com.auroratv.playback.TrackSelector
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** Settings → Audio language "Automatic": English for everything except anime. */
class AudioDefaultTest {
    // A dual-audio file whose default track is Japanese.
    private val dual = listOf(
        MediaStream(index = 0, type = "Video"),
        MediaStream(index = 1, type = "Audio", language = "jpn", isDefault = true),
        MediaStream(index = 2, type = "Audio", language = "eng"),
    )

    private fun pick(lang: String) = TrackSelector.pickAudio(dual, lang, serverDefault = 1)

    @Test
    fun contentKinds() {
        assertEquals(ContentKind.Anime, ContentKind.of(BaseItemDto(id = "a", genres = listOf("Animation", "Drama"), originalTitle = "シャーロット")))
        assertEquals(ContentKind.Anime, ContentKind.of(BaseItemDto(id = "b", genres = listOf("Anime"))))
        assertEquals(ContentKind.Anime, ContentKind.of(BaseItemDto(id = "c", providerIds = mapOf("AniList" to "1"))))
        assertEquals(ContentKind.Anime, ContentKind.of(BaseItemDto(id = "d", genres = listOf("Animation"), productionLocations = listOf("Japan"))))
        assertEquals(ContentKind.Animation, ContentKind.of(BaseItemDto(id = "e", genres = listOf("Animation", "Comedy"), productionLocations = listOf("United States of America"))))
        assertEquals(ContentKind.LiveAction, ContentKind.of(BaseItemDto(id = "f", genres = listOf("Comedy"))))
        // Japanese live action is not anime.
        assertEquals(ContentKind.LiveAction, ContentKind.of(BaseItemDto(id = "g", genres = listOf("Drama"), productionLocations = listOf("Japan"), originalTitle = "七人の侍")))
    }

    @Test
    fun nonAnimeMovieDefaultsToEnglish() = runTest {
        val server = FakeServer { json("{}") }
        val film = BaseItemDto(id = "m", type = "Movie", genres = listOf("Drama"), productionLocations = listOf("Japan"))
        val lang = PlaybackPreparer.automaticAudioLanguage(api(server), film, dual, 1)
        assertEquals("eng", lang)
        assertEquals(2, pick(lang), "the English track, not the Japanese default")
        assertEquals(0, server.requests.size, "a movie is classified from itself")
    }

    @Test
    fun animeKeepsItsDefaultTrack() = runTest {
        val server = FakeServer { req ->
            if (req.url.encodedPath == "/Items/show1") json("""{"Id":"show1","Type":"Series","Genres":["Animation","Drama"],"OriginalTitle":"シャーロット"}""")
            else json("{}")
        }
        val ep = BaseItemDto(id = "e1", type = "Episode", seriesId = "show1")
        val lang = PlaybackPreparer.automaticAudioLanguage(api(server), ep, dual, 1)
        assertEquals("", lang)
        assertEquals(1, pick(lang), "the file's own (Japanese) default")
    }

    @Test
    fun liveActionEpisodeGetsEnglishAndNothingIsAskedWhenItDoesntMatter() = runTest {
        val server = FakeServer { req ->
            if (req.url.encodedPath == "/Items/sitcom") json("""{"Id":"sitcom","Type":"Series","Genres":["Comedy"]}""")
            else json("{}")
        }
        val ep = BaseItemDto(id = "e2", type = "Episode", seriesId = "sitcom")
        assertEquals("eng", PlaybackPreparer.automaticAudioLanguage(api(server), ep, dual, 1))
        assertEquals(1, server.requests.size)
        // Default already English, or no English track at all: nothing to decide, no request.
        server.requests.clear()
        val englishDefault = dual.map { if (it.index == 2) it.copy(isDefault = true) else if (it.index == 1) it.copy(isDefault = false) else it }
        assertEquals("", PlaybackPreparer.automaticAudioLanguage(api(server), ep, englishDefault, 2))
        assertEquals("", PlaybackPreparer.automaticAudioLanguage(api(server), ep, dual.filter { it.language != "eng" }, 1))
        assertEquals(0, server.requests.size)
    }
}
