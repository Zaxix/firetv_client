package com.auroratv

import com.auroratv.data.Profile
import com.auroratv.data.SessionManager
import com.auroratv.data.SessionState
import com.auroratv.jellyfin.ItemQuery
import com.auroratv.jellyfin.model.PlaybackReport
import com.auroratv.profiles.LocalWatchStore
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Netflix-style profiles: added on the device under one Jellyfin sign-in (no new server account),
 * each with its own watch history that never touches the account's.
 */
class LocalProfilesTest {
    private val publicInfo = """{"ServerName":"Home","Version":"10.10.7","Id":"s1","ProductName":"Jellyfin Server"}"""

    /** One account ("zak"); every item comes back with the account's own progress (half watched, played). */
    private val episode = """{"Id":"e1","Name":"Pilot","Type":"Episode","SeriesId":"show1","RunTimeTicks":1000000000,
        "UserData":{"PlaybackPositionTicks":500000000,"Played":true,"IsFavorite":true}}"""

    private fun server() = FakeServer { req ->
        when (req.url.encodedPath) {
            "/System/Info/Public" -> json(publicInfo)
            "/Users/AuthenticateByName" -> json("""{"User":{"Id":"u1","Name":"zak","ServerId":"s1"},"AccessToken":"TOKEN-ZAK","ServerId":"s1"}""")
            "/Users/Me" -> json("""{"Id":"u1","Name":"zak"}""")
            "/Items/e1" -> json(episode)
            "/Items" -> json("""{"Items":[$episode],"TotalRecordCount":1}""")
            "/UserItems/Resume" -> json("""{"Items":[$episode],"TotalRecordCount":1}""")
            "/Sessions/Playing", "/Sessions/Playing/Progress", "/Sessions/Playing/Stopped", "/Sessions/Playing/Ping" -> json("", HttpStatusCode.NoContent)
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }

    private suspend fun signedIn(srv: FakeServer, files: MemoryFileStore, vault: MemoryVault): SessionManager {
        val sm = SessionManager(srv.client, files, vault, "TV")
        val (url, info) = sm.discover("jf.local")
        sm.login(sm.addServer(url, info), "zak", "pw")
        return sm
    }

    @Test
    fun addingAProfileNeedsNoServerAccount() = runTest {
        val srv = server(); val files = MemoryFileStore(); val vault = MemoryVault()
        val sm = signedIn(srv, files, vault)
        val logins = srv.requests.count { it.url.encodedPath == "/Users/AuthenticateByName" }

        sm.chooseProfile()
        val kids = assertNotNull(sm.addLocalProfile("Kids", "forest"))
        assertEquals(listOf("zak", "Kids"), sm.profiles.map { it.displayName })
        assertTrue(kids.isLocal)
        assertEquals("forest", kids.avatar)
        assertIs<SessionState.ChoosingProfile>(sm.state.value, "back on Who's watching? with the new profile")

        val opened = mutableListOf<String>()
        sm.onActivate = { opened += it.key }
        sm.selectProfile(sm.profiles.first { it.isLocal })
        val session = assertIs<SessionState.Ready>(sm.state.value).session
        assertEquals("Kids", session.profile.displayName)
        assertNotNull(session.api.local, "the profile's watch history is the device's, not the account's")
        assertTrue(opened.single().endsWith("-p${kids.local!!.id}"), "own settings/history files")
        assertEquals(logins, srv.requests.count { it.url.encodedPath == "/Users/AuthenticateByName" }, "no new sign-in, no new account")
        assertNull(vault.data.keys.firstOrNull { it.contains(kids.local!!.id) }, "no extra secrets stored")

        // Survives a restart, and the app reopens on the profile used last.
        val again = SessionManager(srv.client, files, vault, "TV")
        assertEquals(listOf("zak", "Kids"), again.profiles.map { it.displayName })
        again.start(skipPicker = true)
        assertEquals("Kids", assertIs<SessionState.Ready>(again.state.value).session.profile.displayName)
    }

    @Test
    fun aProfilesViewingStaysOnTheDeviceAndNeverChangesTheAccount() = runTest {
        val srv = server(); val files = MemoryFileStore(); val vault = MemoryVault()
        val sm = signedIn(srv, files, vault)
        val kids = assertNotNull(sm.addLocalProfile("Kids", null))
        sm.selectProfile(kids)
        val api = assertIs<SessionState.Ready>(sm.state.value).session.api

        // Fresh profile: the account's progress, watched mark and favourite don't show through.
        val fresh = api.item("e1")
        assertEquals(0L, fresh.userData?.playbackPositionTicks)
        assertFalse(fresh.userData?.played == true)
        assertFalse(fresh.userData?.isFavorite == true)
        assertTrue(api.resumeItems().isEmpty())

        srv.requests.clear()
        api.reportStart(PlaybackReport(itemId = "e1", playSessionId = "ps1", positionTicks = 0))
        api.reportProgress(PlaybackReport(itemId = "e1", playSessionId = "ps1", positionTicks = 300_000_000))
        api.reportStopped(PlaybackReport(itemId = "e1", playSessionId = "ps1", positionTicks = 300_000_000))
        api.setFavorite("e1", true)
        val paths = srv.requests.map { it.url.encodedPath }
        assertEquals(listOf("/Sessions/Playing/Ping"), paths, "only a keep-alive for the stream; nothing written to the account")

        // Its own Continue Watching and favourites, fetched as plain items.
        val resume = api.resumeItems()
        assertEquals(listOf("e1"), resume.map { it.id })
        assertEquals(300_000_000L, resume.single().userData?.playbackPositionTicks)
        assertFalse(srv.requests.any { it.url.encodedPath == "/UserItems/Resume" })
        srv.requests.clear()
        api.items(ItemQuery(isFavorite = true))
        val favQuery = srv.requests.single { it.url.encodedPath == "/Items" }.url.parameters
        assertEquals("e1", favQuery["ids"]); assertNull(favQuery["isFavorite"])

        // Finishing it (90%+) marks it watched here; "unwatched" queries leave it out.
        api.reportStopped(PlaybackReport(itemId = "e1", playSessionId = "ps1", positionTicks = 950_000_000))
        assertTrue(api.item("e1").userData?.played == true)
        assertTrue(api.resumeItems().isEmpty())
        srv.requests.clear()
        api.items(ItemQuery(filters = listOf("IsUnplayed")))
        val unplayed = srv.requests.single { it.url.encodedPath == "/Items" }.url.parameters
        assertEquals("e1", unplayed["excludeItemIds"]); assertNull(unplayed["filters"])

        // The account itself still reports to the server as before.
        sm.chooseProfile()
        sm.selectProfile(sm.profiles.first { !it.isLocal })
        val account = assertIs<SessionState.Ready>(sm.state.value).session.api
        assertNull(account.local)
        srv.requests.clear()
        account.reportProgress(PlaybackReport(itemId = "e1", playSessionId = "ps2", positionTicks = 1))
        assertEquals(listOf("/Sessions/Playing/Progress"), srv.requests.map { it.url.encodedPath })
        assertEquals(500_000_000L, account.item("e1").userData?.playbackPositionTicks)
    }

    @Test
    fun renameAndRemoveADeviceProfile() = runTest {
        val srv = server(); val files = MemoryFileStore(); val vault = MemoryVault()
        val sm = signedIn(srv, files, vault)
        val kids = assertNotNull(sm.addLocalProfile("Kids", null))
        LocalWatchStore(files, kids.key).setFavorite("e1", true)
        assertNotNull(files.read("watch-${kids.key}.json"))

        sm.updateProfile(kids, "Little ones", "berry")
        val renamed = SessionManager(srv.client, files, vault, "TV").profiles.first { it.isLocal }
        assertEquals("Little ones", renamed.displayName); assertEquals("berry", renamed.avatar)
        assertEquals("zak", sm.profiles.first().displayName, "the account's name is untouched")

        var removed: Profile? = null
        sm.onProfileRemoved = { removed = it }
        sm.removeProfile(sm.profiles.first { it.isLocal })
        assertEquals(kids.key, removed?.key)
        assertEquals(listOf("zak"), sm.profiles.map { it.displayName })
        assertEquals("TOKEN-ZAK", vault.data["token:s1:u1"], "the sign-in stays")
        assertFalse(srv.requests.any { it.url.encodedPath == "/Sessions/Logout" })
    }

    @Test
    fun watchStoreFollowsJellyfinRules() {
        val files = MemoryFileStore()
        var clock = 1_000L
        val store = LocalWatchStore(files, "p", now = { clock })
        val ep = com.auroratv.jellyfin.model.BaseItemDto(id = "e1", type = "Episode", seriesId = "show1", runTimeTicks = 1000)
        store.userData(ep) // seen on screen: runtime and series noted
        store.progress("e1", 30, stopped = true) // 3%: not a resume point
        assertTrue(store.resumeIds(10).isEmpty())
        clock = 2_000L
        store.progress("e1", 500, stopped = true)
        assertEquals(listOf("e1"), store.resumeIds(10))
        assertEquals(listOf("show1"), store.recentSeries(5))
        store.progress("e1", 920, stopped = true) // 92%: watched
        assertEquals(listOf("e1"), store.playedIds(10))
        assertTrue(store.resumeIds(10).isEmpty())
        // Unmarking the series unmarks its episodes; reloading from disk keeps the rest.
        store.setPlayed("show1", false)
        assertFalse(LocalWatchStore(files, "p").userData(ep).played)
    }
}
