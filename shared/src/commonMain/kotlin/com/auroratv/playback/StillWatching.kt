package com.auroratv.playback

import com.auroratv.settings.StillWatchingMode

/**
 * "Are you still watching?": before auto-playing the next episode, ask if nobody has touched the
 * remote for a while, so a sleeping viewer doesn't binge a whole season (and lose their place).
 *
 * Smart scales with what's playing: about three episodes' worth of idle time, but at least 90
 * minutes (so four ~24 min anime episodes play uninterrupted) and at most 4 hours. A movie is never
 * interrupted, because nothing auto-plays after it.
 */
object StillWatching {
    private const val MINUTE = 60_000L

    /** Idle time after which to ask, or null when the check is off. */
    fun thresholdMs(mode: StillWatchingMode, episodeRuntimeMs: Long?): Long? = when (mode) {
        StillWatchingMode.Off -> null
        StillWatchingMode.After1Hour -> 60 * MINUTE
        StillWatchingMode.After2Hours -> 120 * MINUTE
        StillWatchingMode.After3Hours -> 180 * MINUTE
        StillWatchingMode.Smart -> {
            val runtime = episodeRuntimeMs?.takeIf { it > 0 } ?: (30 * MINUTE)
            (runtime * 3).coerceIn(90 * MINUTE, 240 * MINUTE)
        }
    }

    fun shouldAsk(mode: StillWatchingMode, idleMs: Long, episodeRuntimeMs: Long?): Boolean {
        val threshold = thresholdMs(mode, episodeRuntimeMs) ?: return false
        return idleMs >= threshold
    }
}
