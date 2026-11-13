package com.auroratv.jellyfin

/**
 * Turns whatever the user typed ("192.168.1.10", "jellyfin.example.com", "https://x.y/jellyfin/web/#/home")
 * into an ordered list of base URLs to probe.
 */
object ServerAddress {
    const val DEFAULT_HTTP_PORT = 8096
    const val DEFAULT_HTTPS_PORT = 8920

    fun candidates(input: String): List<String> {
        var raw = input.trim()
        if (raw.isEmpty()) return emptyList()

        val explicitScheme = raw.substringBefore("://", "").lowercase().takeIf { raw.contains("://") }
        if (explicitScheme != null) raw = raw.substringAfter("://")

        // Drop the web client part of a pasted URL and any fragment/query.
        raw = raw.substringBefore('#').substringBefore('?')
        val webIndex = raw.indexOf("/web", ignoreCase = true)
        if (webIndex >= 0) raw = raw.substring(0, webIndex)
        raw = raw.trimEnd('/')

        val hostPort = raw.substringBefore('/')
        val path = raw.substring(hostPort.length).trimEnd('/')
        val hasPort = hasExplicitPort(hostPort)
        val host = if (hasPort) hostPort.substringBeforeLast(':') else hostPort
        val isLocal = isLikelyLocal(host)

        val result = mutableListOf<String>()
        fun add(scheme: String, port: Int?) {
            val p = if (port == null) "" else ":$port"
            val url = "$scheme://$host$p$path"
            if (url !in result) result += url
        }

        when {
            explicitScheme != null && hasPort -> result += "$explicitScheme://$hostPort$path"
            explicitScheme == "https" -> { add("https", null); add("https", DEFAULT_HTTPS_PORT) }
            explicitScheme == "http" -> { add("http", null); add("http", DEFAULT_HTTP_PORT) }
            hasPort -> {
                val port = hostPort.substringAfterLast(':').toInt()
                if (port == 443 || port == DEFAULT_HTTPS_PORT) { add("https", port); add("http", port) } else { add("http", port); add("https", port) }
            }
            isLocal -> { add("http", DEFAULT_HTTP_PORT); add("https", DEFAULT_HTTPS_PORT); add("http", null) }
            else -> { add("https", null); add("http", DEFAULT_HTTP_PORT); add("http", null); add("https", DEFAULT_HTTPS_PORT) }
        }
        return result
    }

    private fun hasExplicitPort(hostPort: String): Boolean {
        if (hostPort.startsWith("[")) return hostPort.contains("]:")
        val parts = hostPort.split(':')
        return parts.size == 2 && parts[1].toIntOrNull() != null
    }

    fun isLikelyLocal(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home.arpa")) return true
        if (!h.contains('.')) return true // bare hostname on the LAN
        val octets = h.split('.').map { it.toIntOrNull() ?: return false }
        if (octets.size != 4) return false
        return octets[0] == 10 || octets[0] == 127 ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 169 && octets[1] == 254) ||
            (octets[0] == 100 && octets[1] in 64..127)
    }
}
