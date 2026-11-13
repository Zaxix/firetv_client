package com.auroratv.data

import com.auroratv.core.AppError
import com.auroratv.core.AuroraJson
import com.auroratv.core.Log
import com.auroratv.core.randomHexId
import com.auroratv.core.toAppError
import com.auroratv.jellyfin.ClientIdentity
import com.auroratv.jellyfin.JellyfinApi
import com.auroratv.jellyfin.ServerAddress
import com.auroratv.jellyfin.model.PublicSystemInfo
import com.auroratv.jellyfin.model.UserDto
import com.auroratv.platform.FileStore
import com.auroratv.platform.SecureVault
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SavedUser(val id: String, val name: String, val imageTag: String? = null)

@Serializable
data class SavedServer(
    val id: String,
    val name: String,
    val url: String,
    val version: String? = null,
    val users: List<SavedUser> = emptyList(),
    val lastUserId: String? = null,
)

@Serializable
private data class ServerStoreFile(
    val deviceId: String,
    val servers: List<SavedServer> = emptyList(),
    val lastServerId: String? = null,
)

/** A signed-in user on a server, with an authenticated API client. */
class UserSession(val server: SavedServer, val user: SavedUser, val api: JellyfinApi)

sealed interface SessionState {
    data object Starting : SessionState
    /** No servers saved yet, or the user chose to add one. */
    data object NeedsServer : SessionState
    /** Server chosen but no valid token; [message] explains why (e.g. session expired, offline). */
    data class NeedsLogin(val server: SavedServer, val message: String? = null) : SessionState
    data class Ready(val session: UserSession) : SessionState
}

/**
 * Owns saved servers/users, secure tokens and the active session. Tokens live only in [SecureVault];
 * the servers file holds no secrets.
 */
class SessionManager(
    private val http: HttpClient,
    private val files: FileStore,
    private val vault: SecureVault,
    private val deviceName: String,
) {
    private val tag = "Session"
    private val fileName = "servers.json"
    private var store: ServerStoreFile = load()

    val identity = ClientIdentity(deviceName = deviceName, deviceId = store.deviceId)

    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _servers = MutableStateFlow(store.servers)
    val servers: StateFlow<List<SavedServer>> = _servers.asStateFlow()

    val activeSession: UserSession? get() = (state.value as? SessionState.Ready)?.session

    private fun load(): ServerStoreFile = files.read(fileName)?.let {
        runCatching { AuroraJson.decodeFromString(ServerStoreFile.serializer(), it) }
            .onFailure { e -> Log.w(tag, "Corrupt server store; starting fresh", e) }
            .getOrNull()
    } ?: ServerStoreFile(deviceId = randomHexId())

    private fun save() {
        _servers.value = store.servers
        runCatching { files.write(fileName, AuroraJson.encodeToString(ServerStoreFile.serializer(), store)) }
            .onFailure { Log.e(tag, "Failed to persist servers", it) }
    }

    private fun tokenKey(serverId: String, userId: String) = "token:$serverId:$userId"

    fun apiFor(url: String) = JellyfinApi(http, url, identity)

    /** Restores the last server/user at launch, validating the stored token. */
    suspend fun restore() {
        val server = store.servers.firstOrNull { it.id == store.lastServerId } ?: store.servers.firstOrNull()
        if (server == null) { _state.value = SessionState.NeedsServer; return }
        val user = server.users.firstOrNull { it.id == server.lastUserId }
        val token = user?.let { vault.get(tokenKey(server.id, it.id)) }
        if (user == null || token == null) { _state.value = SessionState.NeedsLogin(server); return }

        val api = JellyfinApi(http, server.url, identity, token, user.id)
        try {
            api.currentUser()
            _state.value = SessionState.Ready(UserSession(server, user, api))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            when (val err = t.toAppError()) {
                is AppError.SessionExpired, is AppError.InvalidCredentials -> {
                    vault.remove(tokenKey(server.id, user.id))
                    _state.value = SessionState.NeedsLogin(server, err.userMessage)
                }
                // Keep the session when merely offline: screens show retryable errors instead of logging out.
                else -> {
                    Log.w(tag, "Server unreachable at startup; continuing with cached session", t)
                    _state.value = SessionState.Ready(UserSession(server, user, api))
                }
            }
        }
    }

    /** Probes candidate URLs for [input]; returns the first that answers as Jellyfin. */
    suspend fun discover(input: String): Pair<String, PublicSystemInfo> {
        val candidates = ServerAddress.candidates(input)
        if (candidates.isEmpty()) throw AppError.Unreachable()
        var lastError: AppError = AppError.Unreachable()
        for (url in candidates) {
            try {
                val (info, resolved) = apiFor(url).publicSystemInfoResolved()
                if (resolved != url) Log.i(tag, "Server redirected $url → $resolved")
                Log.i(tag, "Found Jellyfin ${info.version} at $resolved")
                return resolved to info
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                lastError = t.toAppError()
                Log.d(tag, "No server at $url: ${lastError::class.simpleName}")
            }
        }
        throw lastError
    }

    /** Saves a discovered server (or updates its URL) and moves to login. */
    fun addServer(url: String, info: PublicSystemInfo): SavedServer {
        val id = info.id ?: throw AppError.NotJellyfin()
        val existing = store.servers.firstOrNull { it.id == id }
        val server = (existing ?: SavedServer(id = id, name = info.serverName ?: url, url = url))
            .copy(url = url, name = info.serverName ?: existing?.name ?: url, version = info.version)
        store = store.copy(servers = store.servers.filterNot { it.id == id } + server, lastServerId = id)
        save()
        _state.value = SessionState.NeedsLogin(server)
        return server
    }

    suspend fun publicUsers(server: SavedServer): List<UserDto> =
        runCatching { apiFor(server.url).publicUsers() }.getOrDefault(emptyList())

    suspend fun login(server: SavedServer, username: String, password: String): UserSession {
        // Re-check the address first: a saved http:// URL may now redirect to https://.
        val resolvedUrl = runCatching { apiFor(server.url).publicSystemInfoResolved().second }.getOrDefault(server.url)
        val api = apiFor(resolvedUrl)
        val result = api.authenticate(username.trim(), password)
        val user = SavedUser(result.user.id, result.user.name, result.user.primaryImageTag)
        vault.put(tokenKey(server.id, user.id), result.accessToken)
        val updated = server.copy(url = resolvedUrl, users = server.users.filterNot { it.id == user.id } + user, lastUserId = user.id)
        store = store.copy(servers = store.servers.map { if (it.id == server.id) updated else it }, lastServerId = server.id)
        save()
        val session = UserSession(updated, user, api)
        _state.value = SessionState.Ready(session)
        return session
    }

    /** Switches to a saved user, using their stored token if still valid. */
    suspend fun switchTo(server: SavedServer, user: SavedUser? = null) {
        store = store.copy(lastServerId = server.id)
        val target = user ?: server.users.firstOrNull { it.id == server.lastUserId }
        if (target != null) {
            val updated = server.copy(lastUserId = target.id)
            store = store.copy(servers = store.servers.map { if (it.id == server.id) updated else it })
        }
        save()
        restore()
    }

    fun hasToken(server: SavedServer, user: SavedUser) = vault.get(tokenKey(server.id, user.id)) != null

    fun showAddServer() { _state.value = SessionState.NeedsServer }

    fun showLogin(server: SavedServer, message: String? = null) { _state.value = SessionState.NeedsLogin(server, message) }

    /** Called when any request reports the token is no longer valid. */
    fun onSessionExpired() {
        val session = activeSession ?: return
        vault.remove(tokenKey(session.server.id, session.user.id))
        _state.value = SessionState.NeedsLogin(session.server, AppError.SessionExpired().userMessage)
    }

    suspend fun signOut() {
        val session = activeSession ?: return
        session.api.logout()
        vault.remove(tokenKey(session.server.id, session.user.id))
        _state.value = SessionState.NeedsLogin(session.server)
    }

    fun removeServer(server: SavedServer) {
        server.users.forEach { vault.remove(tokenKey(server.id, it.id)) }
        store = store.copy(
            servers = store.servers.filterNot { it.id == server.id },
            lastServerId = store.lastServerId.takeIf { it != server.id },
        )
        save()
        if (activeSession?.server?.id == server.id || state.value is SessionState.NeedsLogin) {
            val next = store.servers.firstOrNull()
            _state.value = if (next == null) SessionState.NeedsServer else SessionState.NeedsLogin(next)
        }
    }
}
