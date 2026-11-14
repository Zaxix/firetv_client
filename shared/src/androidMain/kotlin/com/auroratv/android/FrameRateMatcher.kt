package com.auroratv.android

import android.app.Activity
import android.os.Build
import android.view.Display
import com.auroratv.core.Log
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Switches the TV's refresh rate to match the video (24p films on a 24 Hz mode instead of 60 Hz),
 * which removes the 3:2 pull-down judder. Keeps the current resolution and restores the original
 * mode when playback ends. Opt-in (Settings → Playback), because many TVs blank for a second or
 * two while switching, and Fire TV's own "Match original frame rate" may already do this.
 */
class FrameRateMatcher(private val activity: Activity) {
    private val tag = "FrameRate"
    private var originalModeId: Int? = null
    private var appliedFor: Float = 0f

    fun match(frameRate: Float) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || frameRate <= 0f || abs(frameRate - appliedFor) < 0.01f) return
        val display = activity.windowManager.defaultDisplay ?: return
        val current = display.mode
        val target = pick(display.supportedModes.toList(), current, frameRate) ?: return
        appliedFor = frameRate
        if (target.modeId == current.modeId) return
        if (originalModeId == null) originalModeId = current.modeId
        Log.i(tag, "Video ${"%.3f".format(frameRate)} fps → ${target.physicalWidth}x${target.physicalHeight} @ ${"%.3f".format(target.refreshRate)} Hz")
        setMode(target.modeId)
    }

    fun restore() {
        val id = originalModeId ?: return
        originalModeId = null
        appliedFor = 0f
        setMode(id)
    }

    private fun setMode(id: Int) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val window = activity.window ?: return
        window.attributes = window.attributes.apply { preferredDisplayModeId = id }
    }

    companion object {
        /** Lowest refresh rate at the current resolution that is a whole multiple of the frame rate. */
        internal fun pick(modes: List<Display.Mode>, current: Display.Mode, fps: Float): Display.Mode? = modes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .mapNotNull { m ->
                val ratio = m.refreshRate / fps
                val whole = ratio.roundToInt()
                if (whole >= 1 && abs(ratio - whole) < 0.01f * whole) m to whole else null
            }
            .minByOrNull { (_, multiple) -> multiple }
            ?.first
    }
}
