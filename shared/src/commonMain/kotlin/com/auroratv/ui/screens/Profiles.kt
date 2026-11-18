package com.auroratv.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.auroratv.data.Profile
import com.auroratv.data.SessionState
import com.auroratv.jellyfin.Artwork
import com.auroratv.ui.AppContainer
import com.auroratv.ui.components.ArtworkImage
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.components.FrostedField
import com.auroratv.ui.components.Glass
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.launch

/** Avatar looks a profile can pick on this device (original gradient discs; no artwork from anywhere). */
object ProfileAvatars {
    data class Avatar(val id: String, val label: String, val from: Color, val to: Color)

    val all = listOf(
        Avatar("aurora", "Aurora", Color(0xFFAA5CC3), Color(0xFF00A4DC)),
        Avatar("sunset", "Sunset", Color(0xFFFF8A4C), Color(0xFFE0457B)),
        Avatar("lagoon", "Lagoon", Color(0xFF1FC8B4), Color(0xFF2A6FDB)),
        Avatar("forest", "Forest", Color(0xFF7BD389), Color(0xFF2F8F5B)),
        Avatar("ember", "Ember", Color(0xFFFFC145), Color(0xFFE5482D)),
        Avatar("berry", "Berry", Color(0xFFE86AD9), Color(0xFF6B3FD6)),
        Avatar("ocean", "Ocean", Color(0xFF5AC8FA), Color(0xFF1C3FAA)),
        Avatar("slate", "Slate", Color(0xFFB8C2CC), Color(0xFF4B5563)),
    )

    fun byId(id: String?) = all.firstOrNull { it.id == id }

    /** A stable default for profiles that haven't picked one (and have no Jellyfin picture). */
    fun defaultFor(userId: String) = all[(userId.hashCode() and 0x7fffffff) % all.size]
}

/** Server picture marker for the avatar picker. */
private const val SERVER_PICTURE = "server"

/** A profile's picture: their chosen gradient, else their Jellyfin picture, else a gradient with their initial. */
@Composable
fun ProfileAvatar(container: AppContainer, profile: Profile, size: Dp, modifier: Modifier = Modifier) {
    val chosen = ProfileAvatars.byId(profile.avatar)
    val serverPicture = if (chosen == null && !profile.isLocal && profile.user.imageTag != null) {
        remember(profile.server.url, profile.user.id, profile.user.imageTag) {
            container.sessions.apiFor(profile.server.url).images.userAvatar(profile.user.id, profile.user.imageTag, 256)
        }
    } else null
    val look = chosen ?: ProfileAvatars.defaultFor(profile.local?.id ?: profile.user.id)
    Box(
        modifier.size(size).clip(CircleShape).background(Brush.linearGradient(listOf(look.from, look.to))),
        contentAlignment = Alignment.Center,
    ) {
        Text(profile.displayName.take(1).uppercase(), style = if (size >= 80.dp) AuroraType.hero else AuroraType.label, color = Color.White)
        if (serverPicture != null) ArtworkImage(Artwork(serverPicture, null), profile.displayName, Modifier.fillMaxSize(), showPlaceholder = false)
    }
}

/**
 * "Who's watching?": every profile on this device, Add profile, and Manage profiles (rename, avatar,
 * remove). Shown when the app opens and from the profile button at the top right.
 */
@Composable
fun ProfilesScreen(container: AppContainer, state: SessionState.ChoosingProfile) {
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    val servers by container.sessions.servers.collectAsState()
    val profiles = remember(servers) { container.sessions.profiles }
    var managing by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<Profile?>(null) }
    var creating by remember { mutableStateOf(false) }
    var askOnLaunch by remember { mutableStateOf(container.sessions.askProfileOnLaunch) }
    val firstFocus = remember { FocusRequester() }
    val current = state.returnTo

    LaunchedEffect(Unit) { repeat(10) { if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(50) } }

    Box(
        Modifier.fillMaxSize().onKeyEvent { ev ->
            if (ev.type != KeyEventType.KeyUp || (ev.key != Key.Back && ev.key != Key.Escape)) return@onKeyEvent false
            when {
                editing != null -> editing = null
                creating -> creating = false
                managing -> managing = false
                !container.sessions.cancelChoosing() -> container.platform.exitApp()
            }
            true
        }
    ) {
        FrostedField(PROFILES_FROST)
        Column(
            Modifier.align(Alignment.Center).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(if (managing) "Manage profiles" else "Who's watching?", style = AuroraType.title, color = Color.White)
            Spacer(Modifier.height(8.dp))
            Text(
                if (managing) "Choose a profile to rename it, change its picture or remove it from this device."
                else "Each profile keeps its own Continue Watching, watch history, settings and subtitles.",
                style = AuroraType.body, color = Color.White.copy(alpha = 0.75f), textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(40.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(28.dp), modifier = Modifier.focusRestorer(firstFocus)) {
                items(profiles, key = { it.key }) { p ->
                    val isFirst = current?.profile?.sameAs(p) ?: (p == profiles.first())
                    ProfileTile(
                        container, p, managing,
                        subtitle = if (profiles.map { it.server.id }.distinct().size > 1) p.server.name else null,
                        modifier = if (isFirst) Modifier.focusRequester(firstFocus) else Modifier,
                    ) {
                        if (managing) editing = p else scope.launch { container.sessions.selectProfile(p) }
                    }
                }
                if (!managing) item(key = "add") {
                    AddProfileTile {
                        // A new profile is just a name and a picture on this device; signing in another
                        // Jellyfin account is only needed when nobody is signed in yet.
                        if (profiles.any { container.sessions.hasToken(it) }) creating = true else container.sessions.addProfile()
                    }
                }
            }
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                AuroraButton(if (managing) "Done" else "Manage profiles", { managing = !managing }, icon = if (managing) null else Icons.Filled.Edit)
                if (managing) AuroraButton(
                    if (askOnLaunch) "Ask on start: On" else "Ask on start: Off",
                    { askOnLaunch = !askOnLaunch; container.sessions.setAskProfileOnLaunch(askOnLaunch) },
                )
            }
        }
        editing?.let { p ->
            ProfileEditor(container, p, onDone = { editing = null; runCatching { firstFocus.requestFocus() } }, onRemove = {
                editing = null
                scope.launch { container.sessions.removeProfile(p) }
            })
        }
        if (creating) ProfileEditor(
            container, null,
            onDone = { creating = false; runCatching { firstFocus.requestFocus() } },
            onRemove = {},
            onCreate = { name, avatar -> container.sessions.addLocalProfile(name, avatar); creating = false },
            onUseAccount = { creating = false; container.sessions.addProfile() },
        )
    }
}

@Composable
private fun ProfileTile(container: AppContainer, profile: Profile, managing: Boolean, subtitle: String?, modifier: Modifier, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(150.dp)) {
        FocusSurface(onClick = onClick, shape = CircleShape, sheen = false, modifier = modifier.size(132.dp)) { focused ->
            Box(contentAlignment = Alignment.Center) {
                ProfileAvatar(container, profile, 132.dp, Modifier.then(if (focused) Modifier.border(4.dp, Color.White, CircleShape) else Modifier))
                if (managing) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.Edit, "Edit", tint = Color.White, modifier = Modifier.size(36.dp))
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(profile.displayName, style = AuroraType.label, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle != null) Text(subtitle, style = AuroraType.caption, color = Color.White.copy(alpha = 0.6f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!container.sessions.hasToken(profile)) Text("Signed out", style = AuroraType.caption, color = Color.White.copy(alpha = 0.6f))
    }
}

@Composable
private fun AddProfileTile(onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(150.dp)) {
        FocusSurface(onClick = onClick, shape = CircleShape, sheen = false, modifier = Modifier.size(132.dp)) { focused ->
            Box(
                Modifier.fillMaxSize().clip(CircleShape).background(if (focused) Color.White else Color.White.copy(alpha = 0.12f))
                    .border(2.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Add, null, tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(48.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text("Add profile", style = AuroraType.label, color = Color.White)
    }
}

/**
 * Rename a profile (on this device), pick its picture, or remove it from this device (asks first).
 * The Jellyfin account itself, and its watch history on the server, are never changed here.
 * With no [profile] it creates a device-only profile ([onCreate]); [onUseAccount] signs in another
 * Jellyfin account instead.
 */
@Composable
private fun ProfileEditor(
    container: AppContainer,
    profile: Profile?,
    onDone: () -> Unit,
    onRemove: () -> Unit,
    onCreate: (name: String, avatar: String) -> Unit = { _, _ -> },
    onUseAccount: () -> Unit = {},
) {
    var name by remember { mutableStateOf(profile?.displayName.orEmpty()) }
    val defaultLook = remember { ProfileAvatars.all[(container.sessions.profiles.size + 1) % ProfileAvatars.all.size].id }
    var avatar by remember {
        mutableStateOf(
            when {
                profile == null -> defaultLook
                profile.avatar != null -> profile.avatar!!
                !profile.isLocal && profile.user.imageTag != null -> SERVER_PICTURE
                else -> ProfileAvatars.defaultFor(profile.local?.id ?: profile.user.id).id
            }
        )
    }
    var confirmRemove by remember { mutableStateOf(false) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(confirmRemove) { repeat(10) { if (runCatching { firstFocus.requestFocus() }.isSuccess) return@LaunchedEffect; kotlinx.coroutines.delay(40) } }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 720.dp).fillMaxWidth(0.7f).clip(RoundedCornerShape(26.dp))
                .background(Glass.fill(Aurora.colors.surface, opaque = true))
                .border(1.dp, Glass.edge, RoundedCornerShape(26.dp))
                .padding(36.dp),
        ) {
            if (confirmRemove && profile != null) {
                Text("Remove ${profile.displayName}?", style = AuroraType.title, color = Aurora.colors.onBackground)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (profile.isLocal) {
                        "This deletes ${profile.displayName}'s Continue Watching, watch history, favourites, settings and search history from this device. " +
                            "Nothing on ${profile.server.name} changes."
                    } else {
                        val extra = profile.user.localProfiles.size
                        "This signs ${profile.displayName} out on this device and deletes their settings, remembered subtitles and search history here" +
                            (if (extra > 0) ", along with the $extra profile${if (extra == 1) "" else "s"} added under this sign-in" else "") + ". " +
                            "Their Jellyfin account, watch history and favourites on ${profile.server.name} stay as they are."
                    },
                    style = AuroraType.body, color = Aurora.colors.onSurfaceMuted,
                )
                Spacer(Modifier.height(26.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AuroraButton("Cancel", { confirmRemove = false }, focusRequester = firstFocus)
                    AuroraButton("Remove profile", onRemove)
                }
            } else {
            Text(if (profile == null) "Add profile" else "Edit profile", style = AuroraType.title, color = Aurora.colors.onBackground)
            if (profile == null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "A profile on this TV with its own Continue Watching, watch history and favourites. No new server account needed.",
                    style = AuroraType.body, color = Aurora.colors.onSurfaceMuted,
                )
            }
            Spacer(Modifier.height(20.dp))
            AuroraTextField(value = name, onValueChange = { name = it.take(20) }, label = if (profile == null) "Name" else "Name on this device", imeAction = androidx.compose.ui.text.input.ImeAction.Done, modifier = Modifier.fillMaxWidth().focusRequester(firstFocus))
            Spacer(Modifier.height(22.dp))
            Text("Picture", style = AuroraType.label, color = Aurora.colors.onSurfaceMuted)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (profile != null && !profile.isLocal && profile.user.imageTag != null) item(key = SERVER_PICTURE) {
                    AvatarChoice(selected = avatar == SERVER_PICTURE, onClick = { avatar = SERVER_PICTURE }) {
                        ProfileAvatar(container, profile.copy(user = profile.user.copy(avatar = null)), 64.dp)
                    }
                }
                items(ProfileAvatars.all, key = { it.id }) { a ->
                    AvatarChoice(selected = avatar == a.id, onClick = { avatar = a.id }) {
                        Box(Modifier.size(64.dp).clip(CircleShape).background(Brush.linearGradient(listOf(a.from, a.to))), contentAlignment = Alignment.Center) {
                            Text(name.take(1).uppercase().ifEmpty { "?" }, style = AuroraType.section, color = Color.White)
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (profile == null) {
                    AuroraButton("Create profile", { if (name.isNotBlank()) onCreate(name.trim(), avatar) }, primary = true, focusKey = "profiles/create")
                    AuroraButton("Cancel", onDone)
                    AuroraButton("Sign in another account", onUseAccount)
                } else {
                    AuroraButton("Save", {
                        container.sessions.updateProfile(profile, name, avatar.takeIf { it != SERVER_PICTURE })
                        onDone()
                    }, primary = true)
                    AuroraButton("Cancel", onDone)
                    AuroraButton("Remove profile", { confirmRemove = true })
                }
            }
            }
        }
    }
}

@Composable
private fun AvatarChoice(selected: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    FocusSurface(onClick = onClick, shape = CircleShape, sheen = false) { focused ->
        Box(
            Modifier.padding(4.dp).then(if (selected || focused) Modifier.border(3.dp, if (focused) Color.White else Aurora.colors.accent, CircleShape) else Modifier).padding(4.dp),
        ) { content() }
    }
}

/** The small profile button at the top right of the app: opens "Who's watching?". */
@Composable
fun ProfileButton(container: AppContainer, profile: Profile, modifier: Modifier = Modifier) {
    FocusSurface(onClick = { container.sessions.chooseProfile() }, shape = CircleShape, sheen = false, modifier = modifier.size(44.dp)) { focused ->
        ProfileAvatar(container, profile, 44.dp, if (focused) Modifier.border(3.dp, Color.White, CircleShape) else Modifier.border(1.dp, Color.White.copy(alpha = 0.3f), CircleShape))
    }
}

/** BlurHash of a soft purple/blue field (Sharkfin's colours) behind the profile screens. */
private const val PROFILES_FROST = "LF7-,j@txbbdz:tpoMnjRgSdaeoM"
