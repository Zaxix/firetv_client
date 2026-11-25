package com.auroratv.discmenu

import com.auroratv.core.AuroraJson
import kotlinx.serialization.Serializable

/**
 * What a menu pack's host serves, so a pack can be fetched file by file instead of as one archive:
 *
 *   <source>/catalog.json                  which films have a pack (ids to match, where its manifest is)
 *   <source>/<pack id>/manifest.json       every file of the pack with its size, SHA-256 and stage
 *   <source>/<pack id>/<path>              the files themselves
 *
 * Any static host works (a web server, a NAS, a private GitHub repository's raw files).
 */
@Serializable
data class CatalogPack(
    val id: String,
    val title: String = "",
    val match: PackMatch = PackMatch(),
    /** Path of the manifest relative to the source. */
    val manifest: String,
)

@Serializable
data class PackCatalog(val schema: Int = 1, val packs: List<CatalogPack> = emptyList()) {
    fun problems(): List<String> = buildList {
        if (schema != 1) add("catalog schema $schema isn't supported (expected 1)")
        packs.forEach { p ->
            if (!PackManifestRules.isSafeId(p.id)) add("pack id '${p.id}' isn't a plain name")
            if (!PackManifestRules.isSafePath(p.manifest)) add("manifest path '${p.manifest}' isn't a safe relative path")
            if (p.match.tmdb.isEmpty() && p.match.imdb.isEmpty() && p.match.titleRegex == null) add("pack '${p.id}' has nothing to match a film by")
        }
    }

    companion object {
        fun parse(json: String): PackCatalog = AuroraJson.decodeFromString(serializer(), json).also { c ->
            val p = c.problems()
            require(p.isEmpty()) { p.joinToString("; ") }
        }
    }
}

/**
 * One file of a pack. [stage] says when the menu needs it, so downloads can be ordered and the menu can start early:
 *  0 = `pack.json` and the intro (the menu can start playing the intro),
 *  1 = the loop, music and sparkle frames (the menu itself can open),
 *  2 = the transition video, page art and highlights (the submenus can open),
 *  3 = the Play Movie letter.
 */
@Serializable
data class ManifestFile(
    val path: String,
    val size: Long,
    val sha256: String,
    val stage: Int = 0,
    /** For an [AssetBundle]: the paths of the files inside it, which the pack refers to as if they were separate files. */
    val contains: List<String> = emptyList(),
)

@Serializable
data class PackManifest(val schema: Int = 1, val id: String, val files: List<ManifestFile>) {
    val totalBytes: Long get() = files.sumOf { it.size }

    /** The files in the order they should be fetched: by stage, then as listed. */
    fun downloadOrder(): List<ManifestFile> = files.withIndex().sortedWith(compareBy({ it.value.stage }, { it.index })).map { it.value }

    /**
     * The highest stage whose files (and every earlier stage's) satisfy [present]; [ALL_STAGES] when everything does,
     * [NO_STAGE] when not even stage 0 does.
     */
    fun readyStage(present: (ManifestFile) -> Boolean): Int {
        var ready = NO_STAGE
        for (stage in files.map { it.stage }.distinct().sorted()) {
            if (!files.filter { it.stage == stage }.all(present)) return ready
            ready = stage
        }
        return if (files.all(present)) ALL_STAGES else ready
    }

    fun problems(): List<String> = buildList {
        if (schema != 1) add("manifest schema $schema isn't supported (expected 1)")
        if (!PackManifestRules.isSafeId(id)) add("pack id '$id' isn't a plain name")
        if (files.isEmpty()) add("the manifest lists no files")
        if (files.size > PackManifestRules.MAX_FILES) add("the manifest lists ${files.size} files (limit ${PackManifestRules.MAX_FILES})")
        if (files.none { it.path == "pack.json" }) add("the manifest doesn't list pack.json")
        if (files.firstOrNull { it.path == "pack.json" }?.stage?.let { it != 0 } == true) add("pack.json must be stage 0")
        files.groupBy { it.path }.filterValues { it.size > 1 }.keys.forEach { add("'$it' is listed twice") }
        for (f in files) {
            if (!PackManifestRules.isSafePath(f.path)) add("unsafe path '${f.path}'")
            if (f.size <= 0L || f.size > PackManifestRules.MAX_FILE_BYTES) add("'${f.path}' has an unusable size (${f.size})")
            if (!PackManifestRules.isSha256(f.sha256)) add("'${f.path}' has no valid SHA-256")
            if (f.stage !in 0..PackManifestRules.MAX_STAGE) add("'${f.path}' has stage ${f.stage}")
        }
        val inside = files.flatMap { it.contains }
        inside.groupBy { it }.filterValues { it.size > 1 }.keys.forEach { add("'$it' is inside more than one bundle") }
        inside.filter { p -> files.any { it.path == p } }.forEach { add("'$it' is both a file and inside a bundle") }
        inside.filterNot { PackManifestRules.isSafePath(it) }.forEach { add("unsafe path '$it' inside a bundle") }
        if (totalBytes > PackManifestRules.MAX_TOTAL_BYTES) add("the pack is ${totalBytes / 1_000_000} MB (limit ${PackManifestRules.MAX_TOTAL_BYTES / 1_000_000} MB)")
    }

    companion object {
        const val NO_STAGE = -1
        const val ALL_STAGES = Int.MAX_VALUE

        fun parse(json: String): PackManifest = AuroraJson.decodeFromString(serializer(), json).also { m ->
            val p = m.problems()
            require(p.isEmpty()) { p.take(4).joinToString("; ") }
        }
    }
}

object PackManifestRules {
    const val MAX_FILES = 400
    const val MAX_FILE_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 512L * 1024 * 1024
    const val MAX_STAGE = 3

    private val id = Regex("[a-z0-9][a-z0-9._-]{0,63}")
    private val sha = Regex("[0-9a-fA-F]{64}")

    fun isSafeId(s: String) = id.matches(s) && !s.contains("..")
    fun isSha256(s: String) = sha.matches(s)

    /** A relative path with plain segments only: nothing that could leave the pack's folder, whatever the file system. */
    fun isSafePath(p: String): Boolean {
        if (p.isEmpty() || p.length > 200 || p.startsWith("/") || p.contains('\\') || p.contains(':') || p.any { it < ' ' }) return false
        return p.split('/').all { seg -> seg.isNotEmpty() && seg != "." && seg != ".." }
    }
}

/** The files a manifest says a pack will have, as the pack checks want to see them (existence only). */
class ManifestAssets(private val paths: Set<String>) : DiscMenuAssets {
    constructor(m: PackManifest) : this(m.files.flatMapTo(HashSet()) { listOf(it.path) + it.contains })
    override fun exists(path: String) = path in paths
    override fun readText(path: String): String = error("manifest view can't read files")
    override fun readBytes(path: String): ByteArray = error("manifest view can't read files")
    override fun decode(bytes: ByteArray, offset: Int, length: Int) = error("manifest view can't decode images")
}

/** Where packs are fetched from: a base URL and, for a private host, a token sent only to that host. */
data class PackSource(val baseUrl: String, val token: String = "") {
    private val base = baseUrl.trim().trimEnd('/')
    val valid: Boolean get() = base.startsWith("https://") || base.startsWith("http://")

    fun url(relative: String): String {
        require(PackManifestRules.isSafePath(relative)) { "unsafe path '$relative'" }
        return "$base/$relative"
    }

    private fun host(u: String) = u.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

    /** Headers for [url]: the token goes only to the source's own host, never to wherever a redirect might lead. */
    fun headersFor(url: String): Map<String, String> =
        if (token.isNotBlank() && host(url) == host(base)) mapOf("Authorization" to "token ${token.trim()}") else emptyMap()
}

/** Whether [address] is on GitHub (the only kind of site the app's built-in disc menu token is sent to). */
fun isGitHubAddress(address: String): Boolean {
    if (!address.trim().lowercase().startsWith("https://")) return false
    val host = address.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@').substringBefore(':').lowercase()
    return host == "github.com" || host == "api.github.com" || host == "raw.githubusercontent.com" || host.endsWith(".githubusercontent.com")
}
