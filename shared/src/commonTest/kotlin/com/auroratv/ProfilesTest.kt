package com.auroratv

import com.auroratv.data.Profile
import com.auroratv.data.SessionManager
import com.auroratv.data.SessionState
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProfilesTest {
    private val publicInfo = """{"ServerName":"Home","Version":"10.10.7","Id":"s1","ProductName":"Jellyfin Server"}"""

    /** A server with two accounts: "zak" (u1) and "mia" (u2). */
    private fun server() = FakeServer { req ->
        when (req.url.encodedPath) {
            "/System/Info/Public" -> json(publicInfo)
            "/Users/AuthenticateByName" -> {
                val body = (req.body as? io.ktor.http.content.TextContent)?.text ?: req.body.toString()
                if (body.contains("mia")) json("""{"User":{"Id":"u2","Name":"mia","ServerId":"s1"},"AccessToken":"TOKEN-MIA","ServerId":"s1"}""")
                else json("""{"User":{"Id":"u1","Name":"zak","ServerId":"s1"},"AccessToken":"TOKEN-ZAK","ServerId":"s1"}""")
            }
            "/Users/Me" -> json("""{"Id":"u1","Name":"zak"}""")
            "/Sessions/Logout" -> json("", HttpStatusCode.NoContent)
            else -> json("{}", HttpStatusCode.NotFound)
        }
    }

    @Test
    fun existingUserBecomesAProfileAndTheAppAsksWhoIsWatching() = runTest {
        val srv = server()
        val files = MemoryFileStore(); val vault = MemoryVault()
        val sm = SessionManager(srv.client, files, vault, "TV")
        val (url, info) = sm.discover("jf.local")
        sm.login(sm.addServer(url, info), "zak", "pw")

        // Next launch: the one existing user is a profile and "Who's watching?" comes first.
        val opened = mutableListOf<String>()
        val sm2 = SessionManager(srv.client, files, vault, "TV").apply { onActivate = { opened += it.displayName } }
        sm2.start()
        assertIs<SessionState.ChoosingProfile>(sm2.state.value)
        assertEquals(listOf("zak"), sm2.profiles.map { it.displayName })
        sm2.selectProfile(sm2.profiles.single())
        assertIs<SessionState.Ready>(sm2.state.value)
        assertEquals(listOf("zak"), opened, "the profile's own data is loaded before the app opens")

        // A link from the TV's home screen skips the question.
        val sm3 = SessionManager(srv.client, files, vault, "TV")
        sm3.start(skipPicker = true)
        assertIs<SessionState.Ready>(sm3.state.value)
    }

    @Test
    fun addRenameAndRemoveProfiles() = runTest {
        val srv = server()
        val files = MemoryFileStore(); val vault = MemoryVault()
        val sm = SessionManager(srv.client, files, vault, "TV")
        val (url, info) = sm.discover("jf.local")
        val home = sm.addServer(url, info)
        sm.login(home, "zak", "pw")

        // Add profile → sign in as another account on the same server.
        sm.chooseProfile()
        sm.addProfile()
        val login = assertIs<SessionState.NeedsLogin>(sm.state.value)
        assertTrue(login.canCancel)
        sm.login(login.server, "mia", "pw")
        assertEquals(listOf("zak", "mia"), sm.profiles.map { it.displayName })

        // Rename and pick an avatar: stays on this device, survives a restart.
        val mia = sm.profiles.first { it.user.id == "u2" }
        sm.updateProfile(mia, "Mia ✨", "sunset")
        val reloaded = SessionManager(srv.client, files, vault, "TV")
        val miaAgain = reloaded.profiles.first { it.user.id == "u2" }
        assertEquals("Mia ✨", miaAgain.displayName)
        assertEquals("sunset", miaAgain.user.avatar)

        // Remove: token and local data go, the other profile stays.
        var removed: Profile? = null
        reloaded.onProfileRemoved = { removed = it }
        reloaded.removeProfile(miaAgain)
        assertEquals("u2", removed?.user?.id)
        assertNull(vault.data["token:s1:u2"])
        assertEquals(listOf("zak"), reloaded.profiles.map { it.displayName })
        assertTrue(vault.data.containsKey("token:s1:u1"))
    }
}
