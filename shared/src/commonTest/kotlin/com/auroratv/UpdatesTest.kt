package com.auroratv

import com.auroratv.settings.Settings
import com.auroratv.update.AppUpdater
import com.auroratv.update.ReleaseAsset
import com.auroratv.update.UpdateChannel
import com.auroratv.update.UpdateManager
import com.auroratv.update.UpdateState
import io.ktor.client.HttpClient
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdatesTest {
    private class FakeUpdater(override val currentBuild: Int, var asset: String? = "AuroraTV-release.apk", var blocker: String? = null) : AppUpdater {
        override val currentVersion = "1.0.$currentBuild"
        val downloads = mutableListOf<Pair<String, ReleaseAsset>>()
        val installs = mutableListOf<String>()
        val opened = mutableListOf<String>()
        var settingsOpened = 0
        override fun pickAsset(names: Collection<String>) = asset?.takeIf { it in names }
        override fun installBlocker() = blocker
        override fun openInstallSettings(): Boolean { settingsOpened++; return true }
        override suspend fun download(http: HttpClient, url: String, asset: ReleaseAsset, onProgress: (Float) -> Unit): String {
            downloads += url to asset; onProgress(1f); return "/tmp/update.apk"
        }
        override suspend fun install(path: String) { installs += path }
        override fun openUrl(url: String) { opened += url }
    }

    private fun versionJson(build: Int, ok: Boolean = true) = """{"build":$build,"version":"1.0.$build","ok":$ok,"changes":["Brand tiles","Faster starts"],
        "assets":{"AuroraTV-release.apk":{"sha256":"ab12","size":1000},"Aurora-TV-1.0.$build.msi":{"sha256":"cd34","size":2000}}}"""

    private fun manager(server: FakeServer, updater: AppUpdater, files: MemoryFileStore = MemoryFileStore(), settings: Settings = Settings(), clock: () -> Long = { 1_000_000L }, scope: kotlinx.coroutines.CoroutineScope) =
        UpdateManager(server.client, updater, files, { settings }, scope, clock)

    @Test
    fun newerPassingBuildIsOfferedAndInstalledAfterChecks() = runTest {
        val server = FakeServer { json(versionJson(40)) }
        val u = FakeUpdater(currentBuild = 30)
        val m = manager(server, u, scope = this)
        m.checkNow()?.join()
        assertEquals("/Zaxix/firetv_client/releases/download/latest-build/version.json", server.requests.single().url.encodedPath)
        assertIs<UpdateState.Available>(m.state.value)
        assertEquals(40, m.prompt.value?.build)
        m.install()?.join()
        assertEquals(UpdateChannel.DOWNLOAD_BASE + "AuroraTV-release.apk", u.downloads.single().first)
        assertEquals("ab12", u.downloads.single().second.sha256, "the checksum from version.json is enforced")
        assertEquals(listOf("/tmp/update.apk"), u.installs)
    }

    @Test
    fun failedOrOlderBuildsAreNeverOffered() = runTest {
        val failing = manager(FakeServer { json(versionJson(50, ok = false)) }, FakeUpdater(30), scope = this)
        failing.checkNow()?.join()
        assertIs<UpdateState.UpToDate>(failing.state.value); assertNull(failing.prompt.value)
        val same = manager(FakeServer { json(versionJson(30)) }, FakeUpdater(30), scope = this)
        same.checkNow()?.join()
        assertIs<UpdateState.UpToDate>(same.state.value)
    }

    @Test
    fun automaticChecksAreDailyAndLaterSnoozesThePrompt() = runTest {
        var now = 1_000_000L
        val files = MemoryFileStore()
        val server = FakeServer { json(versionJson(40)) }
        val m = manager(server, FakeUpdater(30), files, clock = { now }, scope = this)
        m.checkIfDue()?.join()
        assertNotNull(m.prompt.value)
        m.later()
        assertNull(m.prompt.value)
        // Next start a few hours later: no new check, no prompt.
        val m2 = manager(server, FakeUpdater(30), files, clock = { now + 3 * 3_600_000L }, scope = this)
        m2.checkIfDue()?.join()
        assertEquals(1, server.requests.size)
        // A day later it checks again and offers it again.
        now += 25 * 3_600_000L
        val m3 = manager(server, FakeUpdater(30), files, clock = { now }, scope = this)
        m3.checkIfDue()?.join()
        assertNotNull(m3.prompt.value)
        // Turned off in settings: no network at all.
        val off = manager(server, FakeUpdater(30), MemoryFileStore(), Settings(autoCheckUpdates = false), scope = this)
        off.checkIfDue()?.join()
        assertEquals(2, server.requests.size)
    }

    @Test
    fun blockedInstallOpensSettingsAndPortableCopiesOpenThePage() = runTest {
        val server = FakeServer { json(versionJson(40)) }
        val blocked = FakeUpdater(30, blocker = "Allow installs")
        val m = manager(server, blocked, scope = this)
        m.checkNow()?.join()
        m.install()?.join()
        assertEquals(1, blocked.settingsOpened)
        assertTrue(blocked.downloads.isEmpty())
        assertEquals("Allow installs", (m.state.value as UpdateState.Failed).message)

        val portable = FakeUpdater(30, asset = null)
        val p = manager(server, portable, scope = this)
        p.checkNow()?.join()
        p.install()
        assertEquals(listOf(UpdateChannel.PAGE), portable.opened)
    }

    @Test
    fun unreachableReleaseIsReportedQuietly() = runTest {
        val m = manager(FakeServer { json("", HttpStatusCode.NotFound) }, FakeUpdater(30), scope = this)
        m.checkNow()?.join()
        assertIs<UpdateState.Failed>(m.state.value)
        assertNull(m.prompt.value)
    }
}
