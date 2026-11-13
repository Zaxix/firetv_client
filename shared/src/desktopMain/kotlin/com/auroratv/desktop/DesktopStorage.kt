package com.auroratv.desktop

import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.platform.FileStore
import com.auroratv.platform.SecureVault
import com.sun.jna.platform.win32.Crypt32Util
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.Base64

val isWindows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

/** %APPDATA%\AuroraTV on Windows, ~/.config/aurora-tv elsewhere (development). */
fun appDataDir(): File {
    val dir = if (isWindows) File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "AuroraTV")
    else File(System.getProperty("user.home"), ".config/aurora-tv")
    dir.mkdirs()
    return dir
}

fun appCacheDir(): File {
    val dir = if (isWindows) File(System.getenv("LOCALAPPDATA") ?: appDataDir().path, "AuroraTV/Cache")
    else File(System.getProperty("user.home"), ".cache/aurora-tv")
    dir.mkdirs()
    return dir
}

class DesktopFileStore(private val dir: File = appDataDir()) : FileStore {
    override fun read(name: String): String? = File(dir, name).takeIf { it.exists() }?.readText()
    override fun write(name: String, content: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(content)
        Files.move(tmp.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    override fun delete(name: String) { File(dir, name).delete() }
}

/**
 * Tokens encrypted with Windows DPAPI (CryptProtectData, bound to the current Windows user). On
 * non-Windows development machines it falls back to an owner-only file and says so in About.
 */
class DpapiVault(private val files: FileStore) : SecureVault {
    private val fileName = "vault.json"
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    override val description = if (isWindows) "Windows DPAPI (current user)" else "Owner-only file (development fallback, not encrypted)"

    private fun load(): MutableMap<String, String> = files.read(fileName)?.let {
        runCatching { AuroraJson.decodeFromString(serializer, it).toMutableMap() }.getOrNull()
    } ?: mutableMapOf()

    private fun save(map: Map<String, String>) {
        files.write(fileName, AuroraJson.encodeToString(serializer, map))
        if (!isWindows) runCatching {
            Files.setPosixFilePermissions(File(appDataDir(), fileName).toPath(), PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Synchronized
    override fun put(key: String, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val protected = if (isWindows) Crypt32Util.cryptProtectData(bytes) else bytes
        save(load().apply { put(key, Base64.getEncoder().encodeToString(protected)) })
    }

    @Synchronized
    override fun get(key: String): String? {
        val stored = load()[key] ?: return null
        return try {
            val bytes = Base64.getDecoder().decode(stored)
            String(if (isWindows) Crypt32Util.cryptUnprotectData(bytes) else bytes, Charsets.UTF_8)
        } catch (t: Throwable) {
            Log.w("Vault", "Could not decrypt stored secret; removing it", t)
            remove(key)
            null
        }
    }

    @Synchronized
    override fun remove(key: String) {
        save(load().apply { remove(key) })
    }
}
