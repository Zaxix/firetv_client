package com.auroratv

import com.auroratv.core.AppError
import com.auroratv.core.Log
import com.auroratv.data.SessionManager
import com.auroratv.data.SessionState
import com.auroratv.jellyfin.ItemQuery
import com.auroratv.jellyfin.ServerAddress
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JellyfinApiTest {

    private val authOk = """{"User":{"Id":"u1","Name":"zak","ServerId":"s1","HasPassword":true},"AccessToken":"SECRET-TOKEN-123","ServerId":"s1"}"""
    private val publicInfo = """{"ServerName":"Home","Version":"10.10.7","Id":"s1","ProductName":"Jellyfin Server"}"""

    @Test
    fun authenticationSendsClientHeaderAndStoresToken() = runTest {
        val server = FakeServer { req ->
            when (req.url.encodedPath) {
                "/Users/AuthenticateByName" -> json(authOk)
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val a = api(server, token = null, userId = null)
        val result = a.authenticate("zak", "pw")
        assertEquals("SECRET-TOKEN-123", result.accessToken)
        assertEquals("SECRET-TOKEN-123", a.accessToken)
        assertEquals("u1", a.userId)
        val header = server.requests.single().headers["Authorization"]!!
        assertTrue(header.startsWith("MediaBrowser Client=\"Aurora"), header)
        assertTrue(header.contains("DeviceId=\"dev123\""))
        assertFalse(header.contains("Token="), "No token before sign-in")
    }

    @Test
    fun invalidCredentialsMapToFriendlyError() = runTest {
        val server = FakeServer { json("", HttpStatusCode.Unauthorized) }
        assertFailsWith<AppError.InvalidCredentials> { api(server, null, null).authenticate("zak", "wrong") }
    }

    @Test
    fun expiredTokenMapsToSessionExpired() = runTest {
        val server = FakeServer { json("", HttpStatusCode.Unauthorized) }
        assertFailsWith<AppError.SessionExpired> { api(server).userViews() }
    }

    @Test
    fun serverErrorsAndMissingItemsAreDistinguished() = runTest {
        val server = FakeServer { req -> if (req.url.encodedPath.contains("missing")) json("", HttpStatusCode.NotFound) else json("", HttpStatusCode.InternalServerError) }
        assertFailsWith<AppError.NotFound> { api(server).item("missing") }
        val e = assertFailsWith<AppError.Server> { api(server).item("boom") }
        assertEquals(500, e.status)
    }

    @Test
    fun malformedJsonIsReportedAsBadData() = runTest {
        val server = FakeServer { json("{not json") }
        assertFailsWith<AppError.BadData> { api(server).item("x") }
    }

    @Test
    fun paginationParametersAreSent() = runTest {
        val server = FakeServer { json("""{"Items":[{"Id":"a","Name":"A","Type":"Movie"}],"TotalRecordCount":250,"StartIndex":120}""") }
        val r = api(server).items(ItemQuery(parentId = "lib", includeItemTypes = listOf("Movie"), startIndex = 120, limit = 60, filters = listOf("IsUnplayed")))
        assertEquals(250, r.totalRecordCount)
        assertEquals("a", r.items.single().id)
        val url = server.requests.single().url
        assertEquals("120", url.parameters["startIndex"])
        assertEquals("60", url.parameters["limit"])
        assertEquals("lib", url.parameters["parentId"])
        assertEquals("Movie", url.parameters["includeItemTypes"])
        assertEquals("IsUnplayed", url.parameters["filters"])
        assertEquals("u1", url.parameters["userId"])
    }

    @Test
    fun searchSendsSearchTerm() = runTest {
        val server = FakeServer { json("""{"Items":[],"TotalRecordCount":0}""") }
        api(server).items(ItemQuery(searchTerm = "blade runner", limit = 80))
        assertEquals("blade runner", server.requests.single().url.parameters["searchTerm"])
    }

    @Test
    fun unknownFieldsAndMissingOptionalsDecode() = runTest {
        val server = FakeServer {
            json("""{"Id":"e1","Name":"Pilot","Type":"Episode","IndexNumber":1,"ParentIndexNumber":1,"SomethingNew":{"x":1},"UserData":{"PlaybackPositionTicks":600000000,"Played":false},"RunTimeTicks":24000000000}""")
        }
        val item = api(server).item("e1")
        assertEquals("S1:E1", item.episodeLabel)
        assertEquals(60_000L, item.resumePositionMs)
        assertEquals(0.025f, item.progressFraction!!, 0.001f)
    }

    @Test
    fun nonJellyfinServerIsRejected() = runTest {
        val server = FakeServer { json("""{"hello":"world"}""") }
        assertFailsWith<AppError.NotJellyfin> { api(server).publicSystemInfo() }
    }

    @Test
    fun sessionManagerLoginStoresTokenOnlyInVaultAndRestores() = runTest {
        val server = FakeServer { req ->
            when (req.url.encodedPath) {
                "/System/Info/Public" -> json(publicInfo)
                "/Users/AuthenticateByName" -> json(authOk)
                "/Users/Me" -> json("""{"Id":"u1","Name":"zak"}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val files = MemoryFileStore(); val vault = MemoryVault()
        val sm = SessionManager(server.client, files, vault, "Test TV")
        val (url, info) = sm.discover("jf.local")
        val saved = sm.addServer(url, info)
        sm.login(saved, "zak", "pw")
        assertIs<SessionState.Ready>(sm.state.value)
        assertEquals("SECRET-TOKEN-123", vault.data["token:s1:u1"])
        assertFalse(files.data.values.any { it.contains("SECRET-TOKEN-123") }, "Token must never be written to plain files")

        // Relaunch: a new manager restores the session from disk + vault.
        val sm2 = SessionManager(server.client, files, vault, "Test TV")
        sm2.restore()
        val ready = assertIs<SessionState.Ready>(sm2.state.value)
        assertEquals("zak", ready.session.user.name)
        assertEquals(sm.identity.deviceId, sm2.identity.deviceId, "Device id is stable across launches")
    }

    @Test
    fun restoreWithRevokedTokenAsksForLogin() = runTest {
        var revoked = false
        val server = FakeServer { req ->
            when (req.url.encodedPath) {
                "/System/Info/Public" -> json(publicInfo)
                "/Users/AuthenticateByName" -> json(authOk)
                "/Users/Me" -> if (revoked) json("", HttpStatusCode.Unauthorized) else json("""{"Id":"u1","Name":"zak"}""")
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val files = MemoryFileStore(); val vault = MemoryVault()
        val sm = SessionManager(server.client, files, vault, "Test TV")
        val (url, info) = sm.discover("http://jf.local:8096")
        sm.login(sm.addServer(url, info), "zak", "pw")
        revoked = true
        val sm2 = SessionManager(server.client, files, vault, "Test TV")
        sm2.restore()
        val needs = assertIs<SessionState.NeedsLogin>(sm2.state.value)
        assertTrue(needs.message!!.contains("expired"))
        assertNull(vault.data["token:s1:u1"], "Revoked token is deleted")
    }

    @Test
    fun httpToHttpsRedirectIsFollowedAndSavedIncludingSignIn() = runTest {
        val server = FakeServer { req ->
            if (req.url.protocol.name == "http") {
                respond("", HttpStatusCode.MovedPermanently, io.ktor.http.headersOf(io.ktor.http.HttpHeaders.Location, req.url.toString().replace("http://", "https://")))
            } else when (req.url.encodedPath) {
                "/System/Info/Public" -> json(publicInfo)
                "/Users/AuthenticateByName" -> json(authOk)
                else -> json("{}", HttpStatusCode.NotFound)
            }
        }
        val sm = SessionManager(server.client, MemoryFileStore(), MemoryVault(), "TV")
        val (url, info) = sm.discover("http://zaxifin.com")
        assertEquals("https://zaxifin.com", url, "the redirected HTTPS address is what gets saved")
        val saved = sm.addServer(url, info)
        sm.login(saved.copy(url = "http://zaxifin.com"), "zak", "pw") // even an old http:// entry signs in
        val ready = assertIs<SessionState.Ready>(sm.state.value)
        assertEquals("https://zaxifin.com", ready.session.server.url)
    }

    @Test
    fun unreachableServerReportsFriendlyError() = runTest {
        val server = FakeServer { throw IllegalStateException("ConnectException: refused") }
        val sm = SessionManager(server.client, MemoryFileStore(), MemoryVault(), "TV")
        val e = assertFailsWith<AppError> { sm.discover("192.168.1.50") }
        assertTrue(e.userMessage.isNotBlank())
        // All candidate URLs were tried.
        assertEquals(ServerAddress.candidates("192.168.1.50").size, server.requests.size)
    }

    @Test
    fun logRedactsSecrets() {
        val line = Log.redact("""GET /Videos/1/stream?api_key=abc123&static=true Authorization: MediaBrowser Client="x", Token="tok456" {"AccessToken":"zzz","Pw":"hunter2"} X-Emby-Token: qqq""")
        listOf("abc123", "tok456", "zzz", "hunter2", "qqq").forEach { assertFalse(line.contains(it), "$it leaked: $line") }
        assertTrue(line.contains("static=true"))
    }
}
