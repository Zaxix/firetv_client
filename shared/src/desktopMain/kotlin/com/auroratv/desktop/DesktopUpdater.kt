package com.auroratv.desktop

import com.auroratv.core.Log
import com.auroratv.update.AppUpdater
import com.auroratv.update.ReleaseAsset
import io.ktor.client.HttpClient
import java.io.File

/**
 * Windows installs (MSI or EXE installer) update by downloading the new MSI, checking its SHA-256,
 * then closing Aurora and running `msiexec` (a per-user upgrade, no admin prompt) before starting
 * the new version. The portable ZIP and Linux builds open the download page instead.
 */
class DesktopUpdater(private val exitApp: () -> Unit) : AppUpdater {
    override val currentVersion: String = System.getProperty("jpackage.app-version") ?: "dev"
    override val currentBuild: Int = currentVersion.substringAfterLast('.').toIntOrNull()?.takeIf { currentVersion != "dev" } ?: 0

    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val exe: File? = runCatching { ProcessHandle.current().info().command().orElse(null)?.let(::File) }.getOrNull()
    private val portable = exe?.parentFile?.let { File(it, "portable.txt").exists() } ?: true

    override fun pickAsset(names: Collection<String>): String? =
        if (!isWindows || portable || currentBuild == 0 || exe == null) null
        else names.firstOrNull { it.endsWith(".msi", ignoreCase = true) }

    override suspend fun download(http: HttpClient, url: String, asset: ReleaseAsset, onProgress: (Float) -> Unit): String {
        val file = File(System.getProperty("java.io.tmpdir"), "AuroraTV-update.msi")
        UpdateDownload.downloadVerified(http, url, file, asset, onProgress)
        return file.absolutePath
    }

    override suspend fun install(path: String) {
        val app = exe ?: error("Couldn't find the running app to restart it.")
        val script = File(System.getProperty("java.io.tmpdir"), "AuroraTV-update.cmd")
        script.writeText(
            """
            @echo off
            rem Updater: wait for the app to close, upgrade it, start the new version.
            timeout /t 3 /nobreak >nul
            msiexec /i "%~1" /passive /norestart
            if exist "%~2" (start "" "%~2") else (start "" "%~3")
            """.trimIndent().replace("\n", "\r\n")
        )
        // If the install folder was renamed by the upgrade (e.g. "Aurora TV" → "Nameless"), start the new one.
        val renamed = File(app.parentFile?.parentFile, "${com.auroratv.AppInfo.NAME}/${com.auroratv.AppInfo.NAME}.exe")
        ProcessBuilder("cmd.exe", "/c", "start", "${com.auroratv.AppInfo.NAME} update", "/min", script.absolutePath, path, app.absolutePath, renamed.absolutePath)
            .redirectErrorStream(true).start()
        Log.i("Updates", "Installer started; closing Aurora so it can be replaced")
        exitApp()
    }

    override fun openUrl(url: String) {
        runCatching { java.awt.Desktop.getDesktop().browse(java.net.URI(url)) }
            .onFailure { Log.w("Updates", "Couldn't open $url: ${it.message}") }
    }
}
