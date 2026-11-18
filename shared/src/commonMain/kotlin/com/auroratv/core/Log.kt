package com.auroratv.core

/**
 * Tiny logging facade. Every message passes through [redact] before it reaches a sink, so access
 * tokens, API keys and passwords never land in logcat or on disk even if a URL or header is logged.
 */
object Log {
    enum class Level { DEBUG, INFO, WARN, ERROR }

    fun interface Sink {
        fun write(level: Level, tag: String, message: String, error: Throwable?)
    }

    @kotlin.concurrent.Volatile
    var sink: Sink = Sink { level, tag, message, error ->
        println("[$level] $tag: $message" + (error?.let { " (${it::class.simpleName}: ${redact(it.message ?: "")})" } ?: ""))
    }

    var minLevel: Level = Level.DEBUG

    fun d(tag: String, message: String) = log(Level.DEBUG, tag, message, null)
    fun i(tag: String, message: String) = log(Level.INFO, tag, message, null)
    fun w(tag: String, message: String, error: Throwable? = null) = log(Level.WARN, tag, message, error)
    fun e(tag: String, message: String, error: Throwable? = null) = log(Level.ERROR, tag, message, error)

    private fun log(level: Level, tag: String, message: String, error: Throwable?) {
        if (level.ordinal < minLevel.ordinal) return
        runCatching { sink.write(level, tag, redact(message), error) }
    }

    private val patterns = listOf(
        Regex("""(?i)(api_key|apikey|access_token|token|pw|password)=([^&\s"']+)""") to "$1=***",
        Regex("""(?i)(Token=")([^"]*)(")""") to "$1***$3",
        Regex("""(?i)(X-Emby-Token|X-MediaBrowser-Token)(:\s*)(\S+)""") to "$1$2***",
        Regex("""(?i)("(?:AccessToken|Pw|Password)"\s*:\s*")([^"]*)(")""") to "$1***$3",
    )

    /** Removes credentials from arbitrary text. Public so tests can verify it. */
    fun redact(text: String): String = patterns.fold(text) { acc, (regex, replacement) -> regex.replace(acc, replacement) }
}
