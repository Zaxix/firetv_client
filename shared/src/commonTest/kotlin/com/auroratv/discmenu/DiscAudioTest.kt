package com.auroratv.discmenu

import com.auroratv.jellyfin.model.MediaStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiscAudioTest {
    private fun audio(i: Int, lang: String?, ch: Int = 6, title: String? = null, default: Boolean = false) =
        MediaStream(index = i, type = "Audio", language = lang, channels = ch, title = title, isDefault = default)
    private fun sub(i: Int, lang: String?, title: String? = null, forced: Boolean = false) =
        MediaStream(index = i, type = "Subtitle", language = lang, title = title, isForced = forced)

    private val streams = listOf(
        MediaStream(index = 0, type = "Video"),
        audio(1, "eng", default = true), audio(2, "eng", ch = 2, title = "Director's commentary"),
        audio(3, "spa"), audio(4, "fra", ch = 2, title = "Français (France)"), audio(5, "fra", title = "Français (doublé au Québec)"),
        sub(6, "eng"), sub(7, "eng", title = "English SDH"), sub(8, "spa"), sub(9, "fra"), sub(10, "fra", forced = true),
    )

    @Test
    fun picksTheFilmsOwnTrackByLanguage() {
        assertEquals(1, DiscAudio.find(streams, "en")?.index)
        assertEquals(3, DiscAudio.find(streams, "es")?.index)
        assertEquals(5, DiscAudio.find(streams, "fr")?.index)
    }

    @Test
    fun commentaryIsNotADialogueTrack() {
        assertEquals(1, DiscAudio.find(listOf(audio(2, "eng", ch = 2, title = "Audio commentary"), audio(1, "eng")), "en")?.index)
        assertNull(DiscAudio.find(listOf(audio(2, "eng", title = "Audio commentary")), "en"))
    }

    @Test
    fun surroundBeatsStereoAndAMissingLanguageIsUnavailable() {
        assertEquals(7, DiscAudio.find(listOf(audio(6, "spa", ch = 2), audio(7, "spa", ch = 6)), "es")?.index)
        assertFalse(DiscAudio.isAvailable(streams.filterNot { it.language == "spa" }, "es"))
        assertTrue(DiscAudio.isAvailable(streams, "fr"))
        assertNull(DiscAudio.find(streams, "de"))
    }

    @Test
    fun overrideGivesTheStreamIndexOrNoOpinion() {
        assertNull(DiscAudio.override(null))
        assertEquals(3, DiscAudio.override("es")!!(streams))
        assertNull(DiscAudio.override("es")!!(streams.filterNot { it.language == "spa" }))
    }

    @Test
    fun subtitlesForSpanishAndFrenchSkipForcedTracks() {
        assertEquals(8, DiscSubtitles.find(streams, "es")?.index)
        assertEquals(9, DiscSubtitles.find(streams, "fr")?.index)
        assertEquals(6, DiscSubtitles.find(streams, "en")?.index)
        assertTrue(DiscSubtitles.isAvailable(streams, "off"))
        assertFalse(DiscSubtitles.isAvailable(streams.filterNot { it.language == "fra" }, "fr"))
    }
}
