package com.auroratv.desktop

import com.auroratv.core.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.io.File

/** Minimal JNA binding of the libmpv client API (client.h). */
@Suppress("FunctionName")
interface LibMpv : Library {
    fun mpv_client_api_version(): Long
    fun mpv_create(): Pointer?
    fun mpv_initialize(ctx: Pointer): Int
    fun mpv_terminate_destroy(ctx: Pointer)
    fun mpv_set_option_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_set_property_string(ctx: Pointer, name: String, data: String): Int
    fun mpv_get_property_string(ctx: Pointer, name: String): Pointer?
    fun mpv_command(ctx: Pointer, args: Array<String?>): Int
    fun mpv_observe_property(ctx: Pointer, replyUserdata: Long, name: String, format: Int): Int
    fun mpv_wait_event(ctx: Pointer, timeout: Double): Pointer
    fun mpv_wakeup(ctx: Pointer)
    fun mpv_free(data: Pointer)
    fun mpv_error_string(error: Int): String

    companion object {
        const val FORMAT_STRING = 1
        const val FORMAT_FLAG = 3
        const val FORMAT_INT64 = 4
        const val FORMAT_DOUBLE = 5

        const val EVENT_NONE = 0
        const val EVENT_SHUTDOWN = 1
        const val EVENT_START_FILE = 6
        const val EVENT_END_FILE = 7
        const val EVENT_FILE_LOADED = 8
        const val EVENT_PLAYBACK_RESTART = 21
        const val EVENT_PROPERTY_CHANGE = 22

        const val END_REASON_EOF = 0
        const val END_REASON_STOP = 2
        const val END_REASON_QUIT = 3
        const val END_REASON_ERROR = 4

        @Volatile private var cached: LibMpv? = null
        @Volatile var loadError: String? = null
            private set

        /**
         * Loads libmpv-2.dll from the app's bundled resources directory (jpackage), falling back to the
         * library path for development runs.
         */
        fun load(): LibMpv? {
            cached?.let { return it }
            val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
            val resourcesDir = System.getProperty("compose.application.resources.dir")?.let(::File)
            // Also look next to the launcher and in the jpackage app folder, in case the property isn't set.
            val appDir = runCatching { File(LibMpv::class.java.protectionDomain.codeSource.location.toURI()).parentFile }.getOrNull()
            val dirs = listOfNotNull(resourcesDir, appDir, appDir?.let { File(it, "resources") }, appDir?.parentFile).distinct()
            dirs.forEach { com.sun.jna.NativeLibrary.addSearchPath("mpv-2", it.absolutePath); com.sun.jna.NativeLibrary.addSearchPath("libmpv-2", it.absolutePath) }
            val candidates = buildList {
                dirs.forEach { dir -> listOf("libmpv-2.dll", "mpv-2.dll", "libmpv.so.2", "libmpv.so", "libmpv.2.dylib").forEach { add(File(dir, it).absolutePath) } }
                addAll(listOf("libmpv-2", "mpv-2", "mpv"))
            }
            val errors = mutableListOf<String>()
            for (name in candidates) {
                if (name.contains(File.separatorChar) && !File(name).exists()) continue
                try {
                    val lib = Native.load(name, LibMpv::class.java, options)
                    Log.i("libmpv", "Loaded $name (client API ${lib.mpv_client_api_version() shr 16}.${lib.mpv_client_api_version() and 0xffff})")
                    cached = lib
                    return lib
                } catch (t: Throwable) {
                    errors += "$name: ${t.message?.lineSequence()?.firstOrNull()}"
                }
            }
            loadError = "searched ${dirs.joinToString()} (resources.dir=${resourcesDir}); " + errors.joinToString(" | ")
            Log.e("libmpv", "libmpv could not be loaded: $loadError")
            return null
        }
    }
}
