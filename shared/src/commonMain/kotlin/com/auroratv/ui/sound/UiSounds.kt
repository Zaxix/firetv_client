package com.auroratv.ui.sound

import com.auroratv.core.Log
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The two UI sounds (original, generated for Sharkfin; bundled, ~7 KB together). */
enum class UiSound(val file: String) {
    /** Focus moved to another card, button, row or menu item. */
    Move("files/ui_move.wav"),
    /** Something was opened, chosen or confirmed. */
    Select("files/ui_select.wav"),
}

/** Plays bundled sounds with low latency (Android SoundPool). */
interface UiSoundOutput {
    /** Called once per sound at start-up with the bundled file's bytes. */
    fun load(sound: UiSound, bytes: ByteArray)
    /** Plays [sound] at [volume] (0–1), cutting off a previous UI sound still playing so they never pile up. */
    fun play(sound: UiSound, volume: Float)
}

/**
 * Subtle navigation and selection sounds.
 *
 * - **Move** plays when a D-pad/arrow press actually moves focus (one sound per press; a press that
 *   goes nowhere, such as Right at the end of a row, stays silent). Focus moved by the app itself
 *   (opening a screen, restoring focus) makes no sound.
 * - **Select** plays when a focusable item is activated (OK/Enter, or a click).
 * - Silent while a video is playing, when switched off in Settings, or at volume 0. At most one
 *   sound every [MIN_GAP] (holding a key scrolls quickly without a buzz), and each new sound cuts
 *   off the last. The system volume and mute apply (Android plays them as UI sound effects).
 */
object UiSounds {
    @kotlin.concurrent.Volatile var output: UiSoundOutput? = null
    var enabled: Boolean = true
    /** 0–1, from Settings (stored as a percentage). */
    var volume: Float = 0.5f
    /** True while the player is on screen. */
    var suppressed: Boolean = false

    private var pendingMove = false
    private var keyAt: TimeMark = TimeSource.Monotonic.markNow()
    private var lastPlayed: TimeMark? = null
    private val MIN_GAP = 45.milliseconds
    private val KEY_WINDOW = 350.milliseconds

    /** A D-pad/arrow key went down: the next focus change within a moment is the user's move. */
    fun noteDirectionalKey() {
        pendingMove = true
        keyAt = TimeSource.Monotonic.markNow()
    }

    /** Some element just gained focus. */
    fun focusMoved() {
        if (!pendingMove || keyAt.elapsedNow() > KEY_WINDOW) return
        pendingMove = false // one sound per key press, however many nodes report focus
        play(UiSound.Move)
    }

    /** A focusable item was activated. */
    fun selected() {
        pendingMove = false
        play(UiSound.Select)
    }

    private fun play(sound: UiSound) {
        if (!enabled || suppressed || volume <= 0f) return
        val last = lastPlayed
        if (last != null && last.elapsedNow() < MIN_GAP) return
        val out = output ?: return
        lastPlayed = TimeSource.Monotonic.markNow()
        runCatching { out.play(sound, volume) }
        if (com.auroratv.ui.components.UiTrace.on) Log.i("UiSound", sound.name)
    }
}
