package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.MediaStream
import com.auroratv.playback.RememberedTracks
import com.auroratv.playback.TrackMemory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrackMemoryTest {
    private fun a(i: Int, lang: String, title: String? = null, ch: Int = 2) = MediaStream(index = i, type = "Audio", language = lang, title = title, codec = "aac", channels = ch)
    private fun s(i: Int, lang: String, title: String? = null, forced: Boolean = false, codec: String = "ass") = MediaStream(index = i, type = "Subtitle", language = lang, title = title, isForced = forced, codec = codec)

    private val ep1 = BaseItemDto(id = "e1", type = "Episode", seriesId = "show")
    private val ep2 = BaseItemDto(id = "e2", type = "Episode", seriesId = "show")
    private val ep1Streams = listOf(a(1, "eng"), a(2, "jpn"), s(3, "eng", "Signs & Songs"), s(4, "eng", "Full Subtitles"))
    // Next episode: different order and indexes.
    private val ep2Streams = listOf(a(1, "jpn"), a(2, "eng"), s(5, "eng", "Full"), s(6, "eng", "Signs & Songs"), s(7, "spa"))

    @Test
    fun choicesCarryAcrossEpisodesByWhatTheTrackIs() {
        val mem = TrackMemory(MemoryFileStore()) { 1L }
        mem.rememberAudio(ep1, ep1Streams[1])        // Japanese audio
        mem.rememberSubtitle(ep1, ep1Streams[3])     // full English subtitles, not signs & songs
        val r = TrackMemory.apply(mem.get(ep2), ep2Streams)
        assertEquals(1, r.audioIndex)
        assertEquals(RememberedTracks.Track(5), r.subtitle)
    }

    @Test
    fun subtitlesOffIsRememberedAndMissingLanguagesFallBack() {
        val files = MemoryFileStore()
        val mem = TrackMemory(files) { 1L }
        mem.rememberSubtitle(ep1, null)
        assertEquals(RememberedTracks.Off, TrackMemory.apply(mem.get(ep2), ep2Streams).subtitle)
        mem.rememberAudio(ep1, a(9, "fre"))
        assertNull(TrackMemory.apply(mem.get(ep2), ep2Streams).audioIndex, "no French track here: normal preferences apply")
        // Persisted: a new instance (next app start) still knows.
        assertEquals(RememberedTracks.Off, TrackMemory.apply(TrackMemory(files) { 2L }.get(ep2), ep2Streams).subtitle)
    }

    @Test
    fun moviesAreRememberedByTheirOwnId() {
        val mem = TrackMemory(MemoryFileStore()) { 1L }
        val movie = BaseItemDto(id = "m1", type = "Movie")
        mem.rememberAudio(movie, a(2, "jpn"))
        assertEquals(2, TrackMemory.apply(mem.get(movie), listOf(a(1, "eng"), a(2, "jpn"))).audioIndex)
        assertNull(mem.get(ep1))
    }
}
