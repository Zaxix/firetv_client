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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

@Serializable
data class SavedUser(
    val id: String,
    val name: String,
    val imageTag: String? = null,
    /** Name shown for this profile on this device (the Jellyfin user name when null). */
    val nickname: String? = null,
    /** Avatar chosen on this device: a [ProfileAvatars] id, or null for the Jellyfin picture/initial. */
    val avatar: String? = null,
)

/** How long a preferred (earlier) address may still answer after a later one already did. */
private const val PREFERRED_GRACE_MS = 400L
/** Other addresses remembered per server. */
private const val MAX_ADDRESSES = 5
private const val POLL_MS = 20L

@Serializable
data class SavedServer(
    val id: String,
    val name: String,
    val url: String,
    val version: String? = null,
    val users: List<SavedUser> = emptyList(),
    val lastUserId: String? = null,
    /**
     * Other addresses this same server (same Jellyfin id) answered at: e.g. its home-network address
     * and a VPN one (Tailscale 100.x / MagicDNS, WireGuard). Whichever answers is used.
     */
    val addresses: List<String> = emptyList(),
) {
    /** [url] first, then the other known addresses. */
    val allUrls: List<String> get() = (listOf(url) + addresses).distinct()
}

@Serializable
private data class ServerStoreFile(
    val deviceId: String,
    val servers: List<SavedServer> = emptyList(),
    val lastServerId: String? = null,
    /** Show "Who's watching?" when the app opens. */
    val askProfileOnLaunch: Boolean = true,
)

/**
 * A profile is a Jellyfin user signed in on this device. Watch history, Continue Watching and
 * favourites live with that user on the server; settings, remembered tracks, ratings and search
 * history live in this device's per-profile files (see ProfileFiles), keyed by [key].
 */
data class Profile(val server: SavedServer, val user: SavedUser) {
    val key: String get() = "${server.id.take(12)}-${user.id}".filter { it.isLetterOrDigit() || it == '-' }
    val displayName: String get() = user.nickname?.takeIf { it.isNotBlank() } ?: user.name
}

/** A signed-in user on a server, with an authenticated API client. */
class UserSession(val server: SavedServer, val user: SavedUser, val api: JellyfinApi)

sealed interface SessionState {
    data object Starting : SessionState
    /** No servers saved yet, or the user chose to add one. */
    data object NeedsServer : SessionState
    /** Server chosen but no valid token; [message] explains why (e.g. session expired, offline). */
    data class NeedsLogin(val server: SavedServer, val message: String? = null, val username: String? = null, val canCancel: Boolean = false) : SessionState
    data class Ready(val session: UserSession) : SessionState
    /** "Who's watching?": at launch, or from the profile button ([returnTo] is where Back goes). */
    data class ChoosingProfile(val returnTo: UserSession? = null) : SessionState
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

    /** Every profile on this device (signed-in users on saved servers), in the order they were added. */
    val profiles: List<Profile> get() = store.servers.flatMap { s -> s.users.map { Profile(s, it) } }
    val askProfileOnLaunch: Boolean get() = store.askProfileOnLaunch
    fun hasToken(profile: Profile) = hasToken(profile.server, profile.user)

    /** Called just before a profile's session becomes active (to load that profile's local data). */
    var onActivate: (Profile) -> Unit = {}
    /** Called after a profile was removed from this device (to delete its local data). */
    var onProfileRemoved: (Profile) -> Unit = {}

    private fun activate(session: UserSession) {
        runCatching { onActivate(Profile(session.server, session.user)) }.onFailure { Log.e(tag, "Profile data failed to load", it) }
        _state.value = SessionState.Ready(session)
    }

    /**
     * At launch: "Who's watching?" when there are profiles (and that's turned on), otherwise straight
     * in. Opening a specific screen (a link from the TV's home screen) goes straight to the last profile.
     */
    suspend fun start(skipPicker: Boolean = false) {
        if (!skipPicker && store.askProfileOnLaunch && profiles.isNotEmpty()) _state.value = SessionState.ChoosingProfile(null)
        else restore()
    }

    /** From the profile button: show the profiles; Back returns to [activeSession]. */
    fun chooseProfile() { _state.value = SessionState.ChoosingProfile(activeSession ?: (state.value as? SessionState.ChoosingProfile)?.returnTo) }

    /** Back on "Who's watching?" (only when opened from the app). */
    fun cancelChoosing(): Boolean {
        val back = (state.value as? SessionState.ChoosingProfile)?.returnTo ?: return false
        _state.value = SessionState.Ready(back)
        return true
    }

    /** Opens [profile]: straight in with its saved sign-in, or to its server's sign-in screen. */
    suspend fun selectProfile(profile: Profile) {
        val current = activeSession ?: (state.value as? SessionState.ChoosingProfile)?.returnTo
        if (current != null && current.server.id == profile.server.id && current.user.id == profile.user.id) {
            activate(current); return
        }
        if (hasToken(profile)) switchTo(profile.server, profile.user)
        else _state.value = SessionState.NeedsLogin(profile.server, "Sign in again to use this profile.", profile.user.name, canCancel = true)
    }

    /** "Add profile": sign in another user on the current (or first) server. */
    fun addProfile(server: SavedServer? = null) {
        val target = server ?: (state.value as? SessionState.ChoosingProfile)?.returnTo?.server ?: activeSession?.server ?: store.servers.firstOrNull()
        _state.value = if (target == null) SessionState.NeedsServer else SessionState.NeedsLogin(target, null, "", canCancel = true)
    }

    /** Back from a sign-in opened by "Add profile" / a signed-out profile. */
    fun cancelLogin() { _state.value = SessionState.ChoosingProfile(null) }

    fun updateProfile(profile: Profile, nickname: String?, avatar: String?) {
        val user = profile.user.copy(nickname = nickname?.trim()?.takeIf { it.isNotEmpty() && it != profile.user.name }, avatar = avatar)
        replaceUser(profile.server.id, user)
        // Keep an open session (and the picker's way back) pointing at the updated profile.
        (state.value as? SessionState.ChoosingProfile)?.returnTo?.let { r ->
            if (r.user.id == user.id && r.server.id == profile.server.id) _state.value = SessionState.ChoosingProfile(UserSession(serverById(r.server.id) ?: r.server, user, r.api))
        }
    }

    fun setAskProfileOnLaunch(ask: Boolean) { store = store.copy(askProfileOnLaunch = ask); save() }

    /**
     * Removes [profile] from this device: signs it out, forgets its token and deletes its local data.
     * The Jellyfin user and their watch history on the server are untouched.
     */
    suspend fun removeProfile(profile: Profile) {
        val token = vault.get(tokenKey(profile.server.id, profile.user.id))
        if (token != null) runCatching { JellyfinApi(http, profile.server.url, identity, token, profile.user.id).logout() }
        vault.remove(tokenKey(profile.server.id, profile.user.id))
        val server = serverById(profile.server.id) ?: return
        val updated = server.copy(users = server.users.filterNot { it.id == profile.user.id }, lastUserId = server.lastUserId.takeIf { it != profile.user.id })
        store = store.copy(servers = store.servers.map { if (it.id == server.id) updated else it })
        save()
        runCatching { onProfileRemoved(profile) }
        val state = state.value
        val returnTo = (state as? SessionState.ChoosingProfile)?.returnTo
        when {
            profiles.isEmpty() -> _state.value = if (store.servers.isEmpty()) SessionState.NeedsServer else SessionState.NeedsLogin(store.servers.first())
            returnTo != null && returnTo.user.id == profile.user.id -> _state.value = SessionState.ChoosingProfile(null)
            state is SessionState.Ready && state.session.user.id == profile.user.id -> _state.value = SessionState.ChoosingProfile(null)
            else -> _servers.value = store.servers // picker refreshes from [servers]
        }
    }

    private fun serverById(id: String) = store.servers.firstOrNull { it.id == id }

    private fun replaceUser(serverId: String, user: SavedUser) {
        store = store.copy(servers = store.servers.map { s -> if (s.id == serverId) s.copy(users = s.users.map { if (it.id == user.id) user else it }) else s })
        save()
    }

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
        // Open the app straight away with the saved session (Home starts loading at once) and check
        // the token alongside, instead of waiting a round trip before showing anything.
        val session = UserSession(server, user, api)
        activate(session)
        try {
            api.currentUser()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            when (val err = t.toAppError()) {
                is AppError.SessionExpired, is AppError.InvalidCredentials -> {
                    vault.remove(tokenKey(server.id, user.id))
                    if ((_state.value as? SessionState.Ready)?.session === session) _state.value = SessionState.NeedsLogin(server, err.userMessage)
                }
                // Keep the session when merely offline: screens show retryable errors instead of logging out.
                // But first try the server's other addresses (home network ↔ VPN, after a network change).
                else -> {
                    Log.w(tag, "Server unreachable at ${server.url} at startup: ${err::class.simpleName}")
                    val other = if (server.addresses.isEmpty()) null else reachableUrl(server)?.takeIf { it != server.url }
                    if (other != null && (_state.value as? SessionState.Ready)?.session === session) {
                        Log.i(tag, "Server answers at another known address; switching to it")
                        val moved = server.copy(url = other, addresses = (server.allUrls - other).take(MAX_ADDRESSES))
                        store = store.copy(servers = store.servers.map { if (it.id == server.id) moved else it })
                        save()
                        activate(UserSession(moved, user, JellyfinApi(http, other, identity, token, user.id)))
                    }
                }
            }
        }
    }

    /**
     * Probes the addresses [input] could mean (http/https, default ports) all at once and returns the
     * preferred one that answers as Jellyfin. Trying them one after another meant that, over a VPN
     * that silently drops traffic to a wrong port, each wrong guess waited out the full connect timeout.
     */
    suspend fun discover(input: String): Pair<String, PublicSystemInfo> {
        val candidates = ServerAddress.candidates(input)
        if (candidates.isEmpty()) throw AppError.Unreachable()
        val (url, found) = raceInOrder(candidates) { apiFor(it).publicSystemInfoResolved() }.getOrElse { throw it.toAppError() }
        val (info, resolved) = found
        if (resolved != url) Log.i(tag, "Server redirected $url → $resolved")
        Log.i(tag, "Found Jellyfin ${info.version} at $resolved")
        return resolved to info
    }

    /**
     * Which of [server]'s known addresses answers right now as that same server (home network, VPN…),
     * preferring the saved one; null when none does.
     */
    suspend fun reachableUrl(server: SavedServer): String? =
        raceInOrder(server.allUrls) { url ->
            val (info, resolved) = apiFor(url).publicSystemInfoResolved()
            if (info.id != server.id) throw AppError.NotJellyfin() // a different server lives there now
            resolved
        }.getOrNull()?.second

    /**
     * Runs [probe] on every URL at once. Returns the first URL in [urls] order that succeeded, without
     * waiting more than a moment for slower, preferred ones once any has answered. Fails with the most
     * telling error (refused > not Jellyfin > timeout > unreachable) if none did.
     */
    private suspend fun <T> raceInOrder(urls: List<String>, probe: suspend (String) -> T): Result<Pair<String, T>> = coroutineScope {
        val results = arrayOfNulls<Result<T>>(urls.size)
        val jobs = urls.mapIndexed { i, url ->
            launch {
                results[i] = try { Result.success(probe(url)) } catch (c: CancellationException) { throw c } catch (t: Throwable) {
                    Log.d(tag, "No server at $url: ${t.toAppError()::class.simpleName}"); Result.failure(t)
                }
            }
        }
        try {
            var waitedSinceAnswer = -1L // ms since the first address answered (-1: none yet)
            while (true) {
                val winner = urls.indices.firstOrNull { i -> results[i]?.isSuccess == true && (0 until i).all { results[it]?.isFailure == true } }
                    ?: urls.indices.firstOrNull { results[it]?.isSuccess == true }?.takeIf { waitedSinceAnswer >= PREFERRED_GRACE_MS }
                if (winner != null) return@coroutineScope Result.success(urls[winner] to results[winner]!!.getOrThrow())
                if (waitedSinceAnswer >= 0) waitedSinceAnswer += POLL_MS
                else if (results.any { it?.isSuccess == true }) waitedSinceAnswer = 0
                if (results.all { it != null }) {
                    val errors = results.mapNotNull { it?.exceptionOrNull()?.toAppError() }
                    val telling = errors.firstOrNull { it is AppError.Refused } ?: errors.firstOrNull { it is AppError.NotJellyfin }
                        ?: errors.firstOrNull { it is AppError.Timeout } ?: errors.firstOrNull() ?: AppError.Unreachable()
                    return@coroutineScope Result.failure(telling)
                }
                kotlinx.coroutines.delay(POLL_MS)
            }
            @Suppress("UNREACHABLE_CODE") Result.failure(AppError.Unreachable())
        } finally {
            jobs.forEach { it.cancel() }
        }
    }

    /**
     * Saves a discovered server and moves to login. The same server added again at another address
     * (e.g. its VPN address after its home one) keeps both, so either works later.
     */
    fun addServer(url: String, info: PublicSystemInfo): SavedServer {
        val id = info.id ?: throw AppError.NotJellyfin()
        val existing = store.servers.firstOrNull { it.id == id }
        val known = (existing?.allUrls.orEmpty() + listOfNotNull(info.localAddress?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") }))
            .filter { it != url }.distinct().take(MAX_ADDRESSES)
        val server = (existing ?: SavedServer(id = id, name = info.serverName ?: url, url = url))
            .copy(url = url, name = info.serverName ?: existing?.name ?: url, version = info.version, addresses = known)
        store = store.copy(servers = store.servers.filterNot { it.id == id } + server, lastServerId = id)
        save()
        _state.value = SessionState.NeedsLogin(server)
        return server
    }

    suspend fun publicUsers(server: SavedServer): List<UserDto> =
        runCatching { apiFor(server.url).publicUsers() }.getOrDefault(emptyList())

    suspend fun login(server: SavedServer, username: String, password: String): UserSession {
        // Re-check the address first: a saved http:// URL may now redirect to https://, and away from
        // home (or on a VPN) another of the server's known addresses may be the one that answers.
        val resolvedUrl = reachableUrl(server) ?: server.url
        val api = apiFor(resolvedUrl)
        val result = api.authenticate(username.trim(), password)
        val previous = server.users.firstOrNull { it.id == result.user.id }
        val user = SavedUser(result.user.id, result.user.name, result.user.primaryImageTag, previous?.nickname, previous?.avatar)
        vault.put(tokenKey(server.id, user.id), result.accessToken)
        val updated = server.copy(url = resolvedUrl, addresses = (server.allUrls - resolvedUrl).take(MAX_ADDRESSES),
            users = server.users.filterNot { it.id == user.id } + user, lastUserId = user.id)
        store = store.copy(servers = store.servers.map { if (it.id == server.id) updated else it }, lastServerId = server.id)
        save()
        val session = UserSession(updated, user, api)
        activate(session)
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
