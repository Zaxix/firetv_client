package com.auroratv.android

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import com.auroratv.core.Log
import com.auroratv.update.AppUpdater
import com.auroratv.update.ReleaseAsset
import io.ktor.client.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * Updates a sideloaded install from the latest-build release: downloads the release APK, checks its
 * SHA-256, package name, signing key and version against this install, then hands it to Android's
 * PackageInstaller, which shows its own confirmation screen. Debug builds don't self-update.
 */
class AndroidUpdater(private val context: Context) : AppUpdater {
    private val pm = context.packageManager
    private val pkg = context.packageName

    @Suppress("DEPRECATION")
    private fun installedInfo(flags: Int = 0): PackageInfo = pm.getPackageInfo(pkg, flags)

    override val currentBuild: Int
        get() = runCatching {
            val info = installedInfo()
            (if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()).toInt()
        }.getOrDefault(0)

    override val currentVersion: String get() = runCatching { installedInfo().versionName }.getOrNull() ?: "unknown"

    private val debuggable get() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    override fun pickAsset(names: Collection<String>): String? =
        if (debuggable) null else RELEASE_APKS.firstOrNull { it in names }

    override fun installBlocker(): String? =
        if (Build.VERSION.SDK_INT >= 26 && !pm.canRequestPackageInstalls())
            "Allow ${com.auroratv.AppInfo.NAME} to install updates: in the screen that opens, turn on \"Allow from this source\" for ${com.auroratv.AppInfo.NAME} " +
                "(on Fire TV: Settings → My Fire TV → Developer options → Install unknown apps → ${com.auroratv.AppInfo.NAME}), then choose Update again."
        else null

    override fun openInstallSettings(): Boolean {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= 26) {
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$pkg")))
                add(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES))
            }
            add(Intent(Settings.ACTION_SECURITY_SETTINGS))
        }
        for (i in intents) {
            try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return true } catch (_: ActivityNotFoundException) {} catch (_: SecurityException) {}
        }
        return false
    }

    override suspend fun download(http: HttpClient, url: String, asset: ReleaseAsset, onProgress: (Float) -> Unit): String {
        val file = File(context.cacheDir, "updates/update.apk")
        UpdateDownload.downloadVerified(http, url, file, asset, onProgress)
        verifyApk(file)
        return file.absolutePath
    }

    /** Same package, same signing key, newer version: anything else is refused before Android sees it. */
    @Suppress("DEPRECATION")
    private fun verifyApk(file: File) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val archive = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("The downloaded file isn't a valid app package.")
        if (archive.packageName != pkg) error("The downloaded package is ${archive.packageName}, not ${com.auroratv.AppInfo.NAME}.")
        val newCode = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        if (newCode <= currentBuild) error("The download isn't newer than this version.")
        val mine = signers(installedInfo(flags))
        val theirs = signers(archive)
        if (mine.isNotEmpty() && theirs.isNotEmpty() && mine != theirs) error("The update is signed with a different key; it was not installed.")
    }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.let { if (it.hasMultipleSigners()) it.apkContentsSigners else it.signingCertificateHistory } else info.signatures
        return sigs.orEmpty().map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }.toSet()
    }

    override suspend fun install(path: String) = withContext(Dispatchers.IO) {
        val file = File(path)
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply { setAppPackageName(pkg) }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("aurora.apk", 0, file.length()).use { out ->
                    input.copyTo(out, 1 shl 16)
                    session.fsync(out)
                }
            }
            val intent = Intent(context, UpdateInstallReceiver::class.java).setPackage(pkg)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
        Log.i(TAG, "Update handed to the system installer (session $id)")
    }

    override fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { Toast.makeText(context, "Open $url on another device to download the update.", Toast.LENGTH_LONG).show() }
    }

    companion object {
        const val RELEASE_APK = "Nameless-release.apk"
        /** Current name first; the old name is still published so builds from before the rename can update. */
        val RELEASE_APKS = listOf(RELEASE_APK, "AuroraTV-release.apk")
        private const val TAG = "Updates"
    }
}

/** Receives PackageInstaller results: shows Android's confirmation screen, or the failure reason. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { Log.w("Updates", "Couldn't show the install confirmation: ${it.message}") }
            }
            PackageInstaller.STATUS_SUCCESS -> Log.i("Updates", "Update installed")
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
                Log.w("Updates", "Update not installed: $message")
                if (status != PackageInstaller.STATUS_FAILURE_ABORTED)
                    Toast.makeText(context, "${com.auroratv.AppInfo.NAME} update wasn't installed: $message", Toast.LENGTH_LONG).show()
            }
        }
    }
}
