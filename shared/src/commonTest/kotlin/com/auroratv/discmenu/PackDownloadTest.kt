package com.auroratv.discmenu

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

private fun file(path: String, stage: Int, size: Long = 100) = ManifestFile(path, size, SHA, stage)

private fun manifest(vararg files: ManifestFile) = PackManifest(1, "mini", files.toList())

class PackManifestTest {
    private val good = manifest(file("letter.mp4", 3), file("pack.json", 0), file("intro.mp4", 0), file("loop.mp4", 1), file("pages/a.webp", 2))

    @Test
    fun aGoodManifestHasNoProblemsAndIsFetchedByStageThenListedOrder() {
        assertEquals(emptyList(), good.problems())
        assertEquals(listOf("pack.json", "intro.mp4", "loop.mp4", "pages/a.webp", "letter.mp4"), good.downloadOrder().map { it.path })
        assertEquals(500L, good.totalBytes)
    }

    @Test
    fun pathsThatCouldLeaveThePackFolderAreRefused() {
        for (bad in listOf("../x", "a/../../x", "/etc/passwd", "a\\b", "a//b", "./a", "c:evil", "", "a/")) {
            assertFalse(PackManifestRules.isSafePath(bad), bad)
            assertTrue(manifest(file("pack.json", 0), file(bad, 1)).problems().isNotEmpty(), bad)
        }
        assertTrue(PackManifestRules.isSafePath("page_sprites/ss1/tag_1.webp"))
    }

    @Test
    fun badManifestsAreRejectedWithAReason() {
        assertTrue(manifest(file("intro.mp4", 0)).problems().any { "pack.json" in it })
        assertTrue(manifest(file("pack.json", 0), file("pack.json", 0)).problems().any { "twice" in it })
        assertTrue(manifest(file("pack.json", 0), ManifestFile("a", 5, "nothex", 1)).problems().any { "SHA-256" in it })
        assertTrue(manifest(file("pack.json", 0), file("a", 1, size = 0)).problems().any { "size" in it })
        assertTrue(manifest(file("pack.json", 0), file("a", 9)).problems().any { "stage" in it })
        assertTrue(manifest(file("pack.json", 1)).problems().any { "stage 0" in it })
        assertTrue(manifest(file("pack.json", 0, size = PackManifestRules.MAX_FILE_BYTES + 1)).problems().isNotEmpty())
        assertFailsWith<IllegalArgumentException> { PackManifest.parse("""{"schema":1,"id":"Bad Id","files":[]}""") }
    }

    @Test
    fun readyStageIsTheHighestStageWhoseFilesAndEarlierOnesArePresent() {
        fun ready(vararg have: String) = good.readyStage { it.path in have }
        assertEquals(PackManifest.NO_STAGE, ready())
        assertEquals(PackManifest.NO_STAGE, ready("pack.json"))
        assertEquals(0, ready("pack.json", "intro.mp4"))
        assertEquals(0, ready("pack.json", "intro.mp4", "pages/a.webp")) // stage 1 is missing, so stage 2 doesn't count
        assertEquals(1, ready("pack.json", "intro.mp4", "loop.mp4"))
        assertEquals(2, ready("pack.json", "intro.mp4", "loop.mp4", "pages/a.webp"))
        assertEquals(PackManifest.ALL_STAGES, ready("pack.json", "intro.mp4", "loop.mp4", "pages/a.webp", "letter.mp4"))
    }

    @Test
    fun theTokenGoesOnlyToTheSourcesOwnHost() {
        val s = PackSource("https://raw.githubusercontent.com/me/menus/main/", "secret")
        assertEquals("https://raw.githubusercontent.com/me/menus/main/catalog.json", s.url("catalog.json"))
        assertEquals(mapOf("Authorization" to "token secret"), s.headersFor(s.url("catalog.json")))
        assertEquals(emptyMap(), s.headersFor("https://evil.example.com/me/menus/main/catalog.json"))
        assertEquals(emptyMap(), PackSource("https://x.example/m", "").headersFor("https://x.example/m/a"))
        assertFailsWith<IllegalArgumentException> { s.url("../up") }
        assertFalse(PackSource("ftp://x").valid)
        assertFalse(PackSource("").valid)
    }

    @Test
    fun aCatalogNeedsSafeIdsPathsAndSomethingToMatchBy() {
        val ok = PackCatalog.parse("""{"schema":1,"packs":[{"id":"hp1-uk-disc1","title":"HP","match":{"tmdb":["671"]},"manifest":"hp1-uk-disc1/manifest.json"}]}""")
        assertEquals("hp1-uk-disc1", ok.packs.single().id)
        assertFailsWith<IllegalArgumentException> { PackCatalog.parse("""{"packs":[{"id":"a","manifest":"a/manifest.json"}]}""") }
        assertFailsWith<IllegalArgumentException> { PackCatalog.parse("""{"packs":[{"id":"../a","match":{"tmdb":["1"]},"manifest":"m.json"}]}""") }
        assertFailsWith<IllegalArgumentException> { PackCatalog.parse("""{"packs":[{"id":"a","match":{"tmdb":["1"]},"manifest":"../m.json"}]}""") }
    }
}

private class FakeStore : PackStore {
    val files = HashMap<String, Long>()
    var begun = false
    var finished = false
    override fun hasFile(folder: String, path: String, size: Long) = files["$folder/$path"] == size
    override fun begin(folder: String) { begun = true }
    override suspend fun fetch(http: io.ktor.client.HttpClient, url: String, headers: Map<String, String>, folder: String, file: ManifestFile, onBytes: (Long) -> Unit) =
        error("not used: the installer's network step is injected")
    override fun finish(folder: String) { finished = true }
    override fun downloadedFolders() = emptyList<String>()
    override fun delete(folder: String) = Unit
}

class PackInstallTest {
    private val m = manifest(file("letter.mp4", 3), file("pack.json", 0), file("intro.mp4", 0), file("loop.mp4", 1), file("t.mp4", 2))

    @Test
    fun filesComeInStageOrderAndEachStageIsReadyBeforeTheNextIsStarted() = runTest {
        val store = FakeStore()
        val order = mutableListOf<String>()
        val stageWhenStarting = mutableMapOf<String, Int>()
        lateinit var install: PackInstall
        install = PackInstall(m, "mini", store, fetch = { f, onBytes ->
            order += f.path
            stageWhenStarting[f.path] = install.state.value.readyStage
            onBytes(f.size)
            store.files["mini/${f.path}"] = f.size
        })
        install.run()
        assertEquals(listOf("pack.json", "intro.mp4", "loop.mp4", "t.mp4", "letter.mp4"), order)
        assertEquals(PackManifest.NO_STAGE, stageWhenStarting["pack.json"])
        assertEquals(0, stageWhenStarting["loop.mp4"])      // the intro can play while the loop downloads
        assertEquals(1, stageWhenStarting["t.mp4"])          // the menu can open while the submenu files download
        assertEquals(2, stageWhenStarting["letter.mp4"])
        assertEquals(InstallState.Complete, install.state.value)
        assertTrue(store.begun && store.finished)
    }

    @Test
    fun anInterruptedDownloadCarriesOnAndFilesOfTheWrongSizeAreFetchedAgain() = runTest {
        val store = FakeStore()
        store.files["mini/pack.json"] = 100
        store.files["mini/intro.mp4"] = 100
        store.files["mini/loop.mp4"] = 40 // a stale partial file under the final name
        val fetched = mutableListOf<String>()
        val install = PackInstall(m, "mini", store, fetch = { f, _ -> fetched += f.path; store.files["mini/${f.path}"] = f.size })
        assertEquals(0, install.state.value.readyStage)
        install.run()
        assertEquals(listOf("loop.mp4", "t.mp4", "letter.mp4"), fetched)
        assertEquals(InstallState.Complete, install.state.value)
    }

    @Test
    fun aFileThatKeepsFailingStopsTheDownloadAndSaysWhichOne() = runTest {
        val store = FakeStore()
        var attempts = 0
        val install = PackInstall(m, "mini", store, retries = 3, backoffMs = 0, fetch = { f, _ ->
            if (f.path == "loop.mp4") { attempts++; error("HTTP 404") }
            store.files["mini/${f.path}"] = f.size
        })
        install.run()
        assertEquals(3, attempts)
        val failed = assertIs<InstallState.Failed>(install.state.value)
        assertTrue("loop.mp4" in failed.message && "404" in failed.message)
        assertEquals(0, failed.readyStage) // the intro is on the device, the menu isn't
        assertFalse(store.finished)
    }

    @Test
    fun aFileThatFailsOnceIsRetried() = runTest {
        val store = FakeStore()
        var tried = false
        val install = PackInstall(m, "mini", store, backoffMs = 0, fetch = { f, _ ->
            if (f.path == "t.mp4" && !tried) { tried = true; error("timeout") }
            store.files["mini/${f.path}"] = f.size
        })
        install.run()
        assertEquals(InstallState.Complete, install.state.value)
        assertNull((install.state.value as? InstallState.Failed)?.message)
    }
}
