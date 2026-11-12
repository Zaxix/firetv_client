package com.auroratv.desktop

import com.sun.jna.Pointer
import java.io.File

/**
 * Headless check of the bundled libmpv: decodes each given media file (or a generated test pattern)
 * with an external styled ASS file, and reports codec, hardware decoder, subtitle codec and the
 * libass-parsed subtitle text. Run with `Aurora TV.exe --self-test report.txt [files...]` (CI does).
 */
object MpvSelfTest {
    private val ass = """
        [Script Info]
        ScriptType: v4.00+
        PlayResX: 1920
        PlayResY: 1080

        [V4+ Styles]
        Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
        Style: Sign,Arial,72,&H0000FFFF,&H000000FF,&H00000000,&H80000000,-1,0,0,0,100,100,0,0,1,4,2,7,10,10,10,1

        [Events]
        Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
        Dialogue: 0,0:00:00.20,0:00:02.80,Sign,,0,0,0,,{\pos(400,300)\k50}Aurora {\k50}self {\k50}test
    """.trimIndent()

    fun run(reportPath: String, files: List<String>): Int {
        val out = StringBuilder()
        fun log(s: String) { println(s); out.appendLine(s) }
        val lib = LibMpv.load()
        if (lib == null) {
            log("FAIL libmpv not loadable: ${LibMpv.loadError}")
            File(reportPath).writeText(out.toString()); return 1
        }
        val assFile = File.createTempFile("aurora-selftest", ".ass").apply { writeText(ass); deleteOnExit() }
        val targets = files.ifEmpty { listOf("av://lavfi:testsrc2=duration=3:size=1920x1080:rate=24") }
        var failures = 0
        for (target in targets) {
            val ctx = lib.mpv_create() ?: run { log("FAIL mpv_create"); return 1 }
            fun opt(k: String, v: String) = lib.mpv_set_option_string(ctx, k, v)
            fun prop(name: String): String? = lib.mpv_get_property_string(ctx, name)?.let { p -> try { p.getString(0, "UTF-8") } finally { lib.mpv_free(p) } }
            opt("vo", "null"); opt("ao", "null"); opt("hwdec", "auto-safe"); opt("idle", "yes"); opt("terminal", "no")
            opt("sub-auto", "no"); opt("embeddedfonts", "yes")
            lib.mpv_initialize(ctx)
            lib.mpv_command(ctx, arrayOf("loadfile", target, "replace", null))
            var result = "TIMEOUT"
            var details = ""
            var subText: String? = null
            var sampled = false
            val start = System.currentTimeMillis()
            loop@ while (System.currentTimeMillis() - start < 60_000) {
                val ev: Pointer = lib.mpv_wait_event(ctx, 0.25)
                when (ev.getInt(0)) {
                    LibMpv.EVENT_FILE_LOADED -> {
                        // Generated pattern: attach the styled ASS file. Real samples use their embedded/default tracks.
                        if (target.startsWith("av://")) lib.mpv_command(ctx, arrayOf("sub-add", assFile.absolutePath, "select", null))
                        details = listOfNotNull(
                            "mpv=${prop("mpv-version")}", "ffmpeg=${prop("ffmpeg-version")}",
                            "video=${prop("video-codec")}", "format=${prop("video-params/pixelformat") ?: prop("video-format")}",
                            "size=${prop("width")}x${prop("height")}", "audio=${prop("audio-codec-name")}",
                            "audioTracks=${countTracks(::prop, "audio")}", "subTracks=${countTracks(::prop, "sub")}",
                        ).joinToString(" ")
                    }
                    LibMpv.EVENT_END_FILE -> {
                        val data = ev.getPointer(16)
                        val reason = data?.getInt(0)
                        result = if (reason == LibMpv.END_REASON_EOF || (reason == LibMpv.END_REASON_STOP && sampled)) "PASS" else "FAIL(reason=$reason ${data?.getInt(4)?.let { lib.mpv_error_string(it) }})"
                        break@loop
                    }
                    LibMpv.EVENT_NONE -> {
                        if (subText == null) prop("sub-text")?.takeIf { it.isNotBlank() }?.let { subText = it }
                        val pos = prop("time-pos")?.toDoubleOrNull() ?: 0.0
                        // Long files: sample 4 seconds then stop (decode path verified).
                        if (pos > 4.0 && !sampled) { sampled = true; lib.mpv_command(ctx, arrayOf("stop", null)) }
                    }
                    LibMpv.EVENT_PLAYBACK_RESTART -> details += " hwdec=${prop("hwdec-current")} subCodec=${prop("current-tracks/sub/codec")}"
                }
            }
            if (!result.startsWith("PASS")) failures++
            log("$result  $target  $details  subText=${subText?.replace('\n', ' ')}")
            lib.mpv_terminate_destroy(ctx)
        }
        File(reportPath).writeText(out.toString())
        return if (failures == 0) 0 else 2
    }

    private fun countTracks(prop: (String) -> String?, type: String): Int {
        val n = prop("track-list/count")?.toIntOrNull() ?: return 0
        return (0 until n).count { prop("track-list/$it/type") == type }
    }
}
