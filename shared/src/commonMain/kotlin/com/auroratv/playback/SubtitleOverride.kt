package com.auroratv.playback

/** A subtitle choice made before playback starts (e.g. on the disc menu's Languages page). */
sealed interface SubtitleOverride {
    data object Off : SubtitleOverride
    data class Track(val index: Int) : SubtitleOverride
}
