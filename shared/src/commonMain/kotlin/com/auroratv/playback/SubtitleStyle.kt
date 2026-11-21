package com.auroratv.playback

import kotlinx.serialization.Serializable

/**
 * How plain text subtitles (SRT, WebVTT, PGS-free text tracks) look. Styled ASS/SSA subtitles keep
 * their own fonts, colours and positions; this never touches them.
 */
@Serializable
data class SubtitleStyle(
    val color: SubtitleColor = SubtitleColor.White,
    val edge: SubtitleEdge = SubtitleEdge.Outline,
    val background: SubtitleBackground = SubtitleBackground.None,
    val bold: Boolean = false,
    /** How far above the bottom edge, in percent of the picture height (0 = the usual spot). */
    val raisePercent: Int = 0,
    /** Brightness of the text in HDR videos, in percent (bright white HDR text can glare). */
    val hdrBrightnessPercent: Int = 80,
) {
    /** The text colour as 0xAARRGGBB, dimmed for HDR videos. */
    fun textArgb(hdr: Boolean): Long {
        val f = if (hdr) hdrBrightnessPercent.coerceIn(30, 100) / 100f else 1f
        val c = color.argb
        fun ch(shift: Int) = (((c shr shift) and 0xFF) * f).toLong().coerceIn(0, 255)
        return (0xFFL shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    companion object {
        val RaiseChoices = listOf(0, 5, 10, 15, 20)
        val HdrChoices = listOf(100, 90, 80, 70, 60, 50)
    }
}

enum class SubtitleColor(val label: String, val argb: Long) {
    White("White", 0xFFFFFFFF),
    Yellow("Yellow", 0xFFFFE14D),
    Cyan("Cyan", 0xFF6FE3FF),
    Green("Green", 0xFF8CF29A),
    Grey("Soft grey", 0xFFD2D2D2),
}

enum class SubtitleEdge(val label: String) { Outline("Outline"), Shadow("Drop shadow"), None("None") }

enum class SubtitleBackground(val label: String, val alpha: Int) {
    None("None", 0),
    Translucent("See-through box", 0x99),
    Solid("Solid box", 0xEE),
}

/** The next value in a list of choices, wrapping round (for "press to change" setting rows). */
fun <T> List<T>.cycleAfter(current: T): T = this[(indexOf(current) + 1) % size]

/** mpv option values for [style] (mpv colours are "#AARRGGBB"); used by both mpv engines. */
object MpvSubtitleStyle {
    fun options(style: SubtitleStyle, hdr: Boolean, fontFamily: String? = null): List<Pair<String, String>> = buildList {
        // Plain text subtitles only (mpv leaves ASS/SSA styling alone unless told to override it).
        add("sub-font" to (fontFamily ?: "sans-serif"))
        add("sub-color" to hex(style.textArgb(hdr)))
        add("sub-bold" to if (style.bold) "yes" else "no")
        when (style.edge) {
            SubtitleEdge.Outline -> { add("sub-border-size" to "3"); add("sub-shadow-offset" to "0") }
            SubtitleEdge.Shadow -> { add("sub-border-size" to "0.8"); add("sub-shadow-offset" to "2.5") }
            SubtitleEdge.None -> { add("sub-border-size" to "0"); add("sub-shadow-offset" to "0") }
        }
        add("sub-border-color" to "#FF000000")
        add("sub-shadow-color" to "#C0000000")
        if (style.background == SubtitleBackground.None) {
            add("sub-border-style" to "outline-and-shadow")
            add("sub-back-color" to "#00000000")
        } else {
            add("sub-border-style" to "background-box")
            add("sub-back-color" to hex((style.background.alpha.toLong() shl 24)))
        }
        // mpv's subtitle margin is in 720-line units; the default is 22.
        add("sub-margin-y" to (22 + style.raisePercent * 720 / 100).toString())
    }

    private fun hex(argb: Long) = "#" + argb.toString(16).padStart(8, '0').uppercase()
}
