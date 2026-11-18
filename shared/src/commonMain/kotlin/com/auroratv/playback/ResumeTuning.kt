package com.auroratv.playback

/**
 * Start-up/resume optimisations, switchable only so CI can time them against each other on the same
 * build, file and emulator (`--ei resumeLevel 0|1|2`). Users always get [CURRENT].
 *
 * - 0: how Play worked originally: exact-position start, a separate HTTP stack for video, item
 *   details and the PlaybackInfo negotiation one after the other.
 * - 1: the previous build: starts at the keyframe before the saved position, video over the app's
 *   warm connections.
 * - 2: also asks for the item and negotiates playback at the same time.
 * - 3: now: also keeps the start of font-heavy MKVs (anime releases) on the device and fetches it
 *   ahead (MediaHeader).
 */
object ResumeTuning {
    const val CURRENT = 3

    @kotlin.concurrent.Volatile
    var level: Int = CURRENT

    val keyframeStart get() = level >= 1
    val sharedConnections get() = level >= 1
    val parallelPrepare get() = level >= 2
    val headerCache get() = level >= 3
}
