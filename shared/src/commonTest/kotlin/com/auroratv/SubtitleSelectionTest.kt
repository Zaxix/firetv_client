package com.auroratv

import com.auroratv.jellyfin.model.BaseItemDto
import com.auroratv.jellyfin.model.MediaStream
import com.auroratv.playback.RememberedTracks
import com.auroratv.playback.TrackMemory
import com.auroratv.playback.TrackSelector
import com.auroratv.playback.TrackSelector.SubtitleKind
import com.auroratv.settings.SubtitleMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Automatic subtitle choice: the dialogue (full, then SDH/CC, then other tracks) whenever the audio
 * isn't in the viewer's language, and Signs & Songs only for dubs, "Only forced", a hand-picked
 * choice, or when it's the only track there is. Default settings throughout: Default mode and no
 * preferred language (English).
 */
class SubtitleSelectionTest {
    private val video = MediaStream(0, "Video", codec = "hevc")
    private val japanese = MediaStream(1, "Audio", codec = "flac", language = "jpn", isDefault = true)
    private val englishDub = MediaStream(2, "Audio", codec = "aac", language = "eng")
    private fun sub(index: Int, title: String?, language: String? = "eng", default: Boolean = false, forced: Boolean = false, hi: Boolean = false) =
        MediaStream(index, "Subtitle", codec = "ass", language = language, title = title, isDefault = default, isForced = forced, isHearingImpaired = hi)

    private fun pick(streams: List<MediaStream>, audio: String? = "jpn", mode: SubtitleMode = SubtitleMode.Default, preferred: String = "", serverDefault: Int? = null) =
        TrackSelector.pickSubtitle(listOf(video, japanese, englishDub) + streams, mode, preferred, audio, serverDefault)

    @Test
    fun fullTrackBeatsSignsAndSongsHoweverItIsFlagged() {
        // The reported bug: S&S flagged forced (and nothing flagged default) won over the full track.
        assertEquals(11, pick(listOf(sub(10, "Signs & Songs", forced = true), sub(11, "Full Subtitles"))))
        // S&S flagged default and listed first.
        assertEquals(11, pick(listOf(sub(10, "Signs & Songs", default = true), sub(11, "Full Subtitles"))))
        // The server's own default points at S&S.
        assertEquals(11, pick(listOf(sub(10, "Signs/Songs"), sub(11, "English")), serverDefault = 10))
        // Nothing flagged at all: Japanese audio still gets the English dialogue.
        assertEquals(11, pick(listOf(sub(10, "S&S"), sub(11, null))))
        for (mode in listOf(SubtitleMode.Always, SubtitleMode.Smart)) {
            assertEquals(11, pick(listOf(sub(10, "Signs & Songs", default = true, forced = true), sub(11, "Dialogue")), mode = mode), "$mode")
        }
    }

    @Test
    fun sdhBeatsSignsAndSongsButNotAFullTrack() {
        assertEquals(11, pick(listOf(sub(10, "Signs & Songs", default = true), sub(11, "English", hi = true))))
        assertEquals(11, pick(listOf(sub(10, "Signs", forced = true), sub(11, "English SDH"))))
        // Full, SDH and S&S: the full track, even when SDH is flagged default.
        assertEquals(12, pick(listOf(sub(10, "Signs & Songs", forced = true), sub(11, "English [CC]", default = true), sub(12, "English"))))
        // Commentary only after the real tracks.
        assertEquals(11, pick(listOf(sub(10, "Director's Commentary"), sub(11, "English (SDH)"))))
    }

    @Test
    fun onlySignsAndSongsIsStillUsed() {
        assertEquals(10, pick(listOf(sub(10, "Signs & Songs", default = true))))
        assertEquals(10, pick(listOf(sub(10, "Signs & Songs")), mode = SubtitleMode.Always))
        // …but Default mode doesn't switch on an unflagged track for audio of unknown language.
        assertNull(pick(listOf(sub(10, "Signs & Songs")), audio = null))
    }

    @Test
    fun ambiguousNamesAreReadByWhatTheySay() {
        assertEquals(SubtitleKind.Full, TrackSelector.subtitleKind(sub(1, "Full Subtitles (incl. Signs)")))
        assertEquals(SubtitleKind.Full, TrackSelector.subtitleKind(sub(1, "Dialogue + Signs & Songs")))
        assertEquals(SubtitleKind.Full, TrackSelector.subtitleKind(sub(1, "Full", forced = true)), "a full track mislabelled forced")
        assertEquals(SubtitleKind.Full, TrackSelector.subtitleKind(sub(1, "Honorifics")))
        assertEquals(SubtitleKind.Full, TrackSelector.subtitleKind(sub(1, "Sign Language Notes")))
        assertEquals(SubtitleKind.Signs, TrackSelector.subtitleKind(sub(1, "English (Forced)")))
        assertEquals(SubtitleKind.Signs, TrackSelector.subtitleKind(sub(1, "Foreign Parts Only")))
        assertEquals(SubtitleKind.Signs, TrackSelector.subtitleKind(sub(1, "Songs/Signs [Group]")))
        assertEquals(SubtitleKind.Signs, TrackSelector.subtitleKind(sub(1, null, forced = true)))
        assertEquals(SubtitleKind.HearingImpaired, TrackSelector.subtitleKind(sub(1, "English [HI]")))
        assertEquals(SubtitleKind.HearingImpaired, TrackSelector.subtitleKind(sub(1, "Closed Captions")))
        assertEquals(SubtitleKind.Other, TrackSelector.subtitleKind(sub(1, "Commentary")))
        // "Full Subtitles" flagged forced next to a real S&S track: the full one is chosen.
        assertEquals(11, pick(listOf(sub(10, "Signs", forced = true), sub(11, "Full Subtitles", forced = true))))
    }

    @Test
    fun dubsStillGetSignsAndSongs() {
        val tracks = listOf(sub(10, "Signs & Songs", default = true), sub(11, "Full Subtitles"))
        assertEquals(10, pick(tracks, audio = "eng"))
        assertEquals(10, pick(tracks, audio = "eng", mode = SubtitleMode.Smart))
        assertEquals(10, pick(tracks, audio = "eng", mode = SubtitleMode.OnlyForced))
        // An English show with only an unflagged full track: nothing, as before.
        assertNull(pick(listOf(sub(11, "English")), audio = "eng"))
    }

    @Test
    fun missingMetadataFallsBackSensibly() {
        // No language tags anywhere: the untagged full track over untagged S&S.
        assertEquals(11, pick(listOf(sub(10, "Signs & Songs", language = null, default = true), sub(11, null, language = null))))
        assertEquals(11, pick(listOf(sub(10, "Signs", language = "und", forced = true), sub(11, "Subtitles", language = "und"))))
        // No titles, no flags beyond forced: the unforced track carries the dialogue.
        assertEquals(11, pick(listOf(sub(10, null, forced = true), sub(11, null))))
        // English preferred but only Spanish tracks, flagged default: the file's language, dialogue first.
        assertEquals(21, pick(listOf(sub(20, "Señales y canciones", language = "spa", default = true, forced = true), sub(21, "Completo", language = "spa"))))
        // A preferred language still wins over English.
        assertEquals(31, pick(listOf(sub(30, "Full", default = true), sub(31, "Complet", language = "fra")), preferred = "fr"))
    }

    @Test
    fun aHandPickedSignsAndSongsTrackFollowsTheSeries() {
        val files = MemoryFileStore()
        val memory = TrackMemory(files, { 1_000L })
        val ep1 = BaseItemDto(id = "e1", name = "Ep 1", seriesId = "show", type = "Episode")
        val ep2 = BaseItemDto(id = "e2", name = "Ep 2", seriesId = "show", type = "Episode")
        // Episode 1: the viewer switches to S&S by hand.
        memory.rememberSubtitle(ep1, sub(10, "Signs & Songs [Group]"))
        // Next episode: different indexes, S&S listed second; the hand-picked kind is kept.
        val next = listOf(video, japanese, sub(40, "Full Subtitles [Group]"), sub(41, "Signs & Songs [Group]"))
        assertEquals(RememberedTracks.Track(41), TrackMemory.apply(memory.get(ep2), next).subtitle)
        // Without that choice the next episode gets the full track, like the first.
        assertEquals(40, TrackSelector.pickSubtitle(next, SubtitleMode.Default, "", "jpn", null))
        // Turning subtitles off by hand is remembered too.
        memory.rememberSubtitle(ep2, null)
        assertEquals(RememberedTracks.Off, TrackMemory.apply(memory.get(ep1), next).subtitle)
    }
}
