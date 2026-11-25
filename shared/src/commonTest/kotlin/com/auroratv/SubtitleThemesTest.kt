package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.playback.MpvSubtitleStyle
import com.auroratv.playback.SubtitleStyle
import com.auroratv.playback.SubtitleThemes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Film fonts for normal subtitles: the Harry Potter films only (for now), nothing else changes. */
class SubtitleThemesTest {
    private fun movie(name: String, collection: String? = null) =
        BaseItemDto(id = name, name = name, type = "Movie", providerIds = collection?.let { mapOf("TmdbCollection" to it) }.orEmpty())

    @Test
    fun onlyTheHarryPotterFilmsGetTheirFont() {
        assertEquals("wizarding", SubtitleThemes.forItem(movie("Harry Potter and the Philosopher's Stone"))?.id)
        assertEquals("wizarding", SubtitleThemes.forItem(movie("Harry Potter and the Chamber of Secrets"))?.id)
        // Matched by the films' collection whatever the title (another language, an edition name).
        assertEquals("wizarding", SubtitleThemes.forItem(movie("Harry Potter à l'école des sorciers", collection = "1241"))?.id)
        assertNull(SubtitleThemes.forItem(movie("Harry Potter 20th Anniversary: Return to Hogwarts")), "a documentary isn't one of the films")
        assertNull(SubtitleThemes.forItem(movie("Tidewater")))
        assertNull(SubtitleThemes.forItem(movie("Other Film", collection = "999")))
        assertNull(SubtitleThemes.forItem(BaseItemDto(id = "e", name = "Harry Potter and the Quiz", type = "Episode")), "films only")
    }

    @Test
    fun theFontIsReadOnceAndCarriesItsItalic() = runTest {
        var reads = 0
        val read: suspend (String) -> ByteArray = { reads++; ByteArray(10) }
        val font = assertNotNull(SubtitleThemes.font(SubtitleThemes.Wizarding, read))
        assertEquals("IM FELL English", font.family)
        assertEquals(listOf("IMFellEnglish-Regular.ttf", "IMFellEnglish-Italic.ttf"), font.files.map { it.name })
        SubtitleThemes.font(SubtitleThemes.Wizarding, read)
        assertTrue(reads <= 2, "read once per run: $reads")
    }

    @Test
    fun mpvGetsTheFamilyForPlainSubtitlesOnly() {
        val opts = MpvSubtitleStyle.options(SubtitleStyle(), hdr = false, fontFamily = "IM FELL English").toMap()
        assertEquals("IM FELL English", opts["sub-font"])
        assertEquals("sans-serif", MpvSubtitleStyle.options(SubtitleStyle(), hdr = false).toMap()["sub-font"], "every other film keeps the usual font")
        assertTrue("sub-ass-override" !in opts, "styled ASS subtitles keep their own fonts")
    }
}
