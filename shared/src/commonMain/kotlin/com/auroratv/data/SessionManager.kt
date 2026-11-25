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
import com.auroratv.profiles.LocalWatchStore
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
    /** Extra profiles under this sign-in that exist only on this device (see [LocalProfile]). */
    val localProfiles: List<LocalProfile> = emptyList(),
    /** Which of [localProfiles] was used last (null: the account itself). */
    val lastLocalProfileId: String? = null,
)

/**
 * A Netflix-style profile added on this device under an existing Jellyfin sign-in, so nobody has to
 * create another account on the server (which needs an administrator). It browses and plays with
 * that sign-in, but has its own Continue Watching, Next Up, watched marks, favourites and
 * recommendations, kept on this device (LocalWatchStore) and never written to the account.
 */
@Serializable
data class LocalProfile(val id: String, val name: String, val avatar: String? = null)

/** How long a preferred (earlier) address may still answer after a later one already did. */
private const val PREFERRED_GRACE_MS = 400L
/** Other addresses remembered per server. */
private const val MAX_ADDRESSES = 5
private const val POLL_MS = 20L
/** Longest name for a device-only profile. */
private const val MAX_NAME = 20

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
 * A profile is either a Jellyfin user signed in on this device (watch history, Continue Watching and
 * favourites live with that user on the server), or a device-only [local] profile under that sign-in
 * (its watch history lives on this device). Either way settings, remembered tracks, ratings and
 * search history live in this device's per-profile files (see ProfileFiles), keyed by [key].
 */
data class Profile(val server: SavedServer, val user: SavedUser, val local: LocalProfile? = null) {
    val key: String get() = ("${server.id.take(12)}-${user.id}" + (local?.let { "-p${it.id}" } ?: "")).filter { it.isLetterOrDigit() || it == '-' }
    val displayName: String get() = local?.name ?: user.nickname?.takeIf { it.isNotBlank() } ?: user.name
    val avatar: String? get() = if (local != null) local.avatar else user.avatar
    val isLocal: Boolean get() = local != null
    fun sameAs(other: Profile) = server.id == other.server.id && user.id == other.user.id && local?.id == other.local?.id
}

/** A signed-in user on a server, with an authenticated API client; [local] when a device-only profile is using it. */
class UserSession(val server: SavedServer, val user: SavedUser, val api: JellyfinApi, val local: LocalProfile? = null) {
    val profile: Profile get() = Profile(server, user, local)
}

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
    private val splitStores = HashMap<String, com.auroratv.jellyfin.SplitStore>()
    private val orderStores = HashMap<String, com.auroratv.jellyfin.OrderStore>()
    private val fileName = "servers.json"
    private var store: ServerStoreFile = load()

    val identity = ClientIdentity(deviceName = deviceName, deviceId = store.deviceId)

    private val _state = MutableStateFlow<SessionState>(SessionState.Starting)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _servers = MutableStateFlow(store.servers)
    val servers: StateFlow<List<SavedServer>> = _servers.asStateFlow()

    val activeSession: UserSession? get() = (state.value as? SessionState.Ready)?.session

    /** Every profile on this device (signed-in users, each followed by its device-only profiles). */
    val profiles: List<Profile> get() = store.servers.flatMap { s -> s.users.flatMap { u -> listOf(Profile(s, u)) + u.localProfiles.map { Profile(s, u, it) } } }
    /** The device-only profile to open after the next sign-in (one whose account had to sign in again). */
    private var pendingLocalId: String? = null
    val askProfileOnLaunch: Boolean get() = store.askProfileOnLaunch
    fun hasToken(profile: Profile) = hasToken(profile.server, profile.user)

    /** Called just before a profile's session becomes active (to load that profile's local data). */
    var onActivate: (Profile) -> Unit = {}
    /** Called after a profile was removed from this device (to delete its local data). */
    var onProfileRemoved: (Profile) -> Unit = {}

    private fun activate(session: UserSession) {
        runCatching { onActivate(session.profile) }.onFailure { Log.e(tag, "Profile data failed to load", it) }
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
        if (current != null && current.profile.sameAs(profile)) {
            activate(current); return
        }
        if (hasToken(profile)) {
            // Same sign-in, other profile: no server round trip needed, just a fresh client.
            if (current != null && current.server.id == profile.server.id && current.user.id == profile.user.id) {
                rememberLast(profile)
                activate(sessionFor(serverById(profile.server.id) ?: current.server, userOf(profile), vault.get(tokenKey(profile.server.id, profile.user.id)) ?: current.api.accessToken.orEmpty(), profile.local, current.api.baseUrl))
                return
            }
            switchTo(profile.server, profile.user, profile.local)
        } else {
            pendingLocalId = profile.local?.id
            _state.value = SessionState.NeedsLogin(profile.server, "Sign in again to use this profile.", profile.user.name, canCancel = true)
        }
    }

    /** "Add profile": sign in another user on the current (or first) server. */
    fun addProfile(server: SavedServer? = null) {
        pendingLocalId = null
        val target = server ?: (state.value as? SessionState.ChoosingProfile)?.returnTo?.server ?: activeSession?.server ?: store.servers.firstOrNull()
        _state.value = if (target == null) SessionState.NeedsServer else SessionState.NeedsLogin(target, null, "", canCancel = true)
    }

    /** Back from a sign-in opened by "Add profile" / a signed-out profile. */
    fun cancelLogin() { _state.value = SessionState.ChoosingProfile(null) }

    /**
     * "Add profile" without another server account: a device-only profile under the sign-in in use
     * (or the first signed-in one). Null when nobody is signed in on this device yet.
     */
    fun addLocalProfile(name: String, avatar: String?): Profile? {
        val base = (state.value as? SessionState.ChoosingProfile)?.returnTo ?: activeSession
        val (server, user) = base?.let { b -> serverById(b.server.id)?.let { s -> s.users.firstOrNull { it.id == b.user.id }?.let { s to it } } }
            ?: store.servers.firstNotNullOfOrNull { s -> s.users.firstOrNull { hasToken(s, it) }?.let { s to it } }
            ?: return null
        val local = LocalProfile(id = randomHexId().take(10), name = name.trim().ifEmpty { "Profile" }.take(MAX_NAME), avatar = avatar)
        val updated = user.copy(localProfiles = user.localProfiles + local)
        replaceUser(server.id, updated)
        Log.i(tag, "Added a device-only profile")
        return Profile(serverById(server.id) ?: server, updated, local)
    }

    fun updateProfile(profile: Profile, nickname: String?, avatar: String?) {
        val current = serverById(profile.server.id)?.users?.firstOrNull { it.id == profile.user.id } ?: profile.user
        val user = if (profile.local != null) {
            val name = nickname?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME) ?: profile.local.name
            current.copy(localProfiles = current.localProfiles.map { if (it.id == profile.local.id) it.copy(name = name, avatar = avatar) else it })
        } else current.copy(nickname = nickname?.trim()?.takeIf { it.isNotEmpty() && it != current.name }, avatar = avatar)
        replaceUser(profile.server.id, user)
        // Keep an open session (and the picker's way back) pointing at the updated profile.
        fun refreshed(r: UserSession) = UserSession(serverById(r.server.id) ?: r.server, user, r.api, r.local?.let { l -> user.localProfiles.firstOrNull { it.id == l.id } ?: l })
        val st = state.value
        if (st is SessionState.ChoosingProfile) st.returnTo?.takeIf { it.user.id == user.id && it.server.id == profile.server.id }?.let { _state.value = SessionState.ChoosingProfile(refreshed(it)) }
        if (st is SessionState.Ready && st.session.user.id == user.id && st.session.server.id == profile.server.id) _state.value = SessionState.Ready(refreshed(st.session))
    }

    fun setAskProfileOnLaunch(ask: Boolean) { store = store.copy(askProfileOnLaunch = ask); save() }

    /**
     * Removes [profile] from this device: signs it out, forgets its token and deletes its local data.
     * The Jellyfin user and their watch history on the server are untouched.
     */
    suspend fun removeProfile(profile: Profile) {
        if (profile.local != null) return removeLocalProfile(profile)
        val token = vault.get(tokenKey(profile.server.id, profile.user.id))
        if (token != null) runCatching { JellyfinApi(http, profile.server.url, identity, token, profile.user.id).logout() }
        vault.remove(tokenKey(profile.server.id, profile.user.id))
        val server = serverById(profile.server.id) ?: return
        val updated = server.copy(users = server.users.filterNot { it.id == profile.user.id }, lastUserId = server.lastUserId.takeIf { it != profile.user.id })
        store = store.copy(servers = store.servers.map { if (it.id == server.id) updated else it })
        save()
        runCatching { onProfileRemoved(profile) }
        // Device-only profiles under that sign-in go with it (they can't be used without it).
        (server.users.firstOrNull { it.id == profile.user.id }?.localProfiles.orEmpty()).forEach { l -> runCatching { onProfileRemoved(Profile(server, profile.user, l)) } }
        val state = state.value
        val returnTo = (state as? SessionState.ChoosingProfile)?.returnTo
        when {
            profiles.isEmpty() -> _state.value = if (store.servers.isEmpty()) SessionState.NeedsServer else SessionState.NeedsLogin(store.servers.first())
            returnTo != null && returnTo.user.id == profile.user.id -> _state.value = SessionState.ChoosingProfile(null)
            state is SessionState.Ready && state.session.user.id == profile.user.id -> _state.value = SessionState.ChoosingProfile(null)
            else -> _servers.value = store.servers // picker refreshes from [servers]
        }
    }

    /** Removes a device-only profile and its history from this device; the account is untouched. */
    private fun removeLocalProfile(profile: Profile) {
        val local = profile.local ?: return
        val server = serverById(profile.server.id) ?: return
        val user = server.users.firstOrNull { it.id == profile.user.id } ?: return
        replaceUser(server.id, user.copy(localProfiles = user.localProfiles.filterNot { it.id == local.id },
            lastLocalProfileId = user.lastLocalProfileId.takeIf { it != local.id }))
        runCatching { onProfileRemoved(profile) }
        val st = state.value
        val returnTo = (st as? SessionState.ChoosingProfile)?.returnTo
        if (returnTo?.local?.id == local.id || (st is SessionState.Ready && st.session.local?.id == local.id)) _state.value = SessionState.ChoosingProfile(null)
    }

    private fun serverById(id: String) = store.servers.firstOrNull { it.id == id }

    private fun userOf(profile: Profile) = serverById(profile.server.id)?.users?.firstOrNull { it.id == profile.user.id } ?: profile.user

    /** Remembers [profile] as the one to open next time. */
    private fun rememberLast(profile: Profile) {
        val user = userOf(profile).copy(lastLocalProfileId = profile.local?.id)
        store = store.copy(lastServerId = profile.server.id, servers = store.servers.map { s ->
            if (s.id == profile.server.id) s.copy(lastUserId = user.id, users = s.users.map { if (it.id == user.id) user else it }) else s
        })
        save()
    }

    /** A session for [user] on [server], as [local] when given (its own watch history on this device). */
    private fun sessionFor(server: SavedServer, user: SavedUser, token: String, local: LocalProfile?, url: String = server.url): UserSession {
        val api = JellyfinApi(http, url, identity, token, user.id)
        val key = Profile(server, user, local).key
        if (local != null) api.local = LocalWatchStore(files, key)
        // Episodes stored several to a file: the server's splits are shared, each profile's progress is its own.
        api.virtual = com.auroratv.jellyfin.VirtualEpisodes(splitStore(server.id), LocalWatchStore(files, key, base = "split-watch"))
        // Episode orders (DVD, intended, hand-made) describe the shows, so they're shared on a server too.
        api.order = com.auroratv.jellyfin.EpisodeOrders(orderStore(server.id), orderAuto)
        api.orderSource = orderSource
        return UserSession(server, user, api, local)
    }

    /**
     * Every profile on [serverId] this device knows, for the household's Top 10: a client per account
     * profile (its own sign-in, at [baseUrl]: the address that's answering now) and each device-only
     * profile's watch history. Read only.
     */
    fun household(serverId: String, baseUrl: String): Pair<List<JellyfinApi>, List<LocalWatchStore>> {
        val server = serverById(serverId) ?: return emptyList<JellyfinApi>() to emptyList()
        val accounts = server.users.mapNotNull { u -> vault.get(tokenKey(server.id, u.id))?.let { JellyfinApi(http, baseUrl, identity, it, u.id) } }
        val locals = server.users.flatMap { u -> u.localProfiles.map { l -> LocalWatchStore(files, Profile(server, u, l).key) } }
        return accounts to locals
    }

    /** "Automatic episode order" (set by the app from its settings). */
    var orderAuto: () -> Boolean = { true }
    /** Where alternative episode orders come from (TMDB, with the profile's key); set by the app. */
    var orderSource: com.auroratv.jellyfin.TmdbEpisodeGroups? = null

    /** One OrderStore per server, shared by every session on it. Main thread. */
    fun orderStore(serverId: String): com.auroratv.jellyfin.OrderStore =
        orderStores.getOrPut(serverId) { com.auroratv.jellyfin.OrderStore(files, serverId) }

    /** One SplitStore per server, shared by every session on it (they describe the server's files). Main thread. */
    fun splitStore(serverId: String): com.auroratv.jellyfin.SplitStore =
        splitStores.getOrPut(serverId) { com.auroratv.jellyfin.SplitStore(files, serverId) }

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
        val local = user.localProfiles.firstOrNull { it.id == user.lastLocalProfileId }

        // Open the app straight away with the saved session (Home starts loading at once) and check
        // the token alongside, instead of waiting a round trip before showing anything.
        val session = sessionFor(server, user, token, local)
        val api = session.api
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
                        activate(sessionFor(moved, user, token, local, other))
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
        val locals = previous?.localProfiles.orEmpty()
        val local = locals.firstOrNull { it.id == pendingLocalId }
        pendingLocalId = null
        val user = SavedUser(result.user.id, result.user.name, result.user.primaryImageTag, previous?.nickname, previous?.avatar, locals, local?.id)
        vault.put(tokenKey(server.id, user.id), result.accessToken)
        val updated = server.copy(url = resolvedUrl, addresses = (server.allUrls - resolvedUrl).take(MAX_ADDRESSES),
            users = server.users.filterNot { it.id == user.id } + user, lastUserId = user.id)
        store = store.copy(servers = store.servers.map { if (it.id == server.id) updated else it }, lastServerId = server.id)
        save()
        val session = sessionFor(updated, user, result.accessToken, local, resolvedUrl)
        activate(session)
        return session
    }

    /** Switches to a saved user, using their stored token if still valid. */
    suspend fun switchTo(server: SavedServer, user: SavedUser? = null, local: LocalProfile? = null) {
        store = store.copy(lastServerId = server.id)
        val target = user ?: server.users.firstOrNull { it.id == server.lastUserId }
        if (target != null) {
            val updated = server.copy(lastUserId = target.id,
                users = server.users.map { if (it.id == target.id) it.copy(lastLocalProfileId = local?.id) else it })
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
