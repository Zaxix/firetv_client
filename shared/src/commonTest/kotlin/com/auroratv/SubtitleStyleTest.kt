package com.auroratv

import com.auroratv.playback.MpvSubtitleStyle
import com.auroratv.playback.SubtitleBackground
import com.auroratv.playback.SubtitleColor
import com.auroratv.playback.SubtitleEdge
import com.auroratv.playback.SubtitleStyle
import com.auroratv.playback.cycleAfter
import com.auroratv.settings.Settings
import com.auroratv.core.AuroraJson
import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleStyleTest {
    @Test
    fun hdrDimsTheTextOnly() {
        val style = SubtitleStyle(color = SubtitleColor.White, hdrBrightnessPercent = 50)
        assertEquals(0xFFFFFFFFL, style.textArgb(hdr = false))
        assertEquals(0xFF7F7F7FL, style.textArgb(hdr = true))
    }

    @Test
    fun mpvOptionsFollowTheStyle() {
        val plain = MpvSubtitleStyle.options(SubtitleStyle(), hdr = false).toMap()
        assertEquals("#FFFFFFFF", plain["sub-color"])
        assertEquals("3", plain["sub-border-size"])
        assertEquals("outline-and-shadow", plain["sub-border-style"])
        assertEquals("22", plain["sub-margin-y"])

        val boxed = MpvSubtitleStyle.options(
            SubtitleStyle(color = SubtitleColor.Yellow, edge = SubtitleEdge.None, background = SubtitleBackground.Translucent, bold = true, raisePercent = 10),
            hdr = false,
        ).toMap()
        assertEquals("#FFFFE14D", boxed["sub-color"])
        assertEquals("background-box", boxed["sub-border-style"])
        assertEquals("#99000000", boxed["sub-back-color"])
        assertEquals("yes", boxed["sub-bold"])
        assertEquals("94", boxed["sub-margin-y"])
    }

    @Test
    fun choicesCycleAndOldSettingsStillLoad() {
        assertEquals(SubtitleColor.Yellow, SubtitleColor.entries.cycleAfter(SubtitleColor.White))
        assertEquals(SubtitleColor.White, SubtitleColor.entries.cycleAfter(SubtitleColor.Grey))
        // Settings saved before subtitle styles existed load with the defaults.
        val old = AuroraJson.decodeFromString(Settings.serializer(), """{"theme":"Dark","subtitleScale":1.25}""")
        assertEquals(SubtitleStyle(), old.subtitleStyle)
        assertEquals(1.25f, old.subtitleScale)
    }
}
