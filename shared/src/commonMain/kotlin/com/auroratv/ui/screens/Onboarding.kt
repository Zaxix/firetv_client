package com.auroratv.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Login
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auroratv.core.toAppError
import com.auroratv.data.SavedServer
import com.auroratv.jellyfin.model.UserDto
import com.auroratv.ui.AppContainer
import com.auroratv.ui.components.ArtworkImage
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.launch

@Composable
fun AuroraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    password: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    imeAction: ImeAction = ImeAction.Next,
    onIme: () -> Unit = {},
) {
    val colors = Aurora.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
        keyboardActions = KeyboardActions(onAny = { onIme() }),
        textStyle = AuroraType.bodyStrong.copy(color = colors.onBackground),
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = colors.focusRing,
            unfocusedBorderColor = colors.divider,
            focusedLabelColor = colors.onBackground,
            unfocusedLabelColor = colors.onSurfaceFaint,
            cursorColor = colors.onBackground,
        ),
        modifier = modifier,
    )
}

@Composable
private fun OnboardingFrame(title: String, subtitle: String, content: @Composable () -> Unit) {
    val colors = Aurora.colors
    Box(Modifier.fillMaxSize().background(colors.background), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Aurora", style = AuroraType.hero, color = colors.onBackground)
            Spacer(Modifier.height(32.dp))
            Text(title, style = AuroraType.title, color = colors.onBackground, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(subtitle, style = AuroraType.body, color = colors.onSurfaceMuted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(32.dp))
            content()
        }
    }
}

@Composable
fun ConnectScreen(container: AppContainer) {
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val fieldFocus = remember { FocusRequester() }
    val servers by container.sessions.servers.collectAsState()

    fun connect() {
        if (busy || address.isBlank()) return
        busy = true; error = null
        scope.launch {
            try {
                val (url, info) = container.sessions.discover(address)
                container.sessions.addServer(url, info)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (t: Throwable) {
                error = t.toAppError().userMessage
            } finally {
                busy = false
            }
        }
    }

    LaunchedEffect(Unit) { runCatching { fieldFocus.requestFocus() } }

    OnboardingFrame("Connect to Jellyfin", "Enter your server's address, for example 192.168.1.20, jellyfin.local or https://media.example.com") {
        AuroraTextField(
            value = address,
            onValueChange = { address = it; error = null },
            label = "Server address",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            onIme = ::connect,
            modifier = Modifier.fillMaxWidth().focusRequester(fieldFocus),
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = AuroraType.body, color = colors.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        AuroraButton(if (busy) "Connecting…" else "Connect", ::connect, icon = Icons.Filled.Add, primary = true)

        if (servers.isNotEmpty()) {
            Spacer(Modifier.height(40.dp))
            Text("Saved servers", style = AuroraType.section, color = colors.onBackground)
            Spacer(Modifier.height(12.dp))
            servers.forEach { server ->
                ServerRow(server) { scope.launch { container.sessions.switchTo(server) } }
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}

@Composable
private fun ServerRow(server: SavedServer, onClick: () -> Unit) {
    val colors = Aurora.colors
    FocusSurface(onClick = onClick, shape = RoundedCornerShape(14.dp), scaleOnFocus = false, ringWidth = 0.dp, modifier = Modifier.fillMaxWidth()) { focused ->
        Row(
            Modifier.fillMaxWidth().background(if (focused) colors.focusFill else colors.surface).padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.material3.Icon(Icons.Filled.Dns, null, tint = if (focused) colors.background else colors.onSurfaceMuted)
            Spacer(Modifier.width(16.dp))
            Column {
                Text(server.name, style = AuroraType.label, color = if (focused) colors.background else colors.onBackground)
                Text(server.url, style = AuroraType.caption, color = if (focused) colors.background.copy(alpha = 0.7f) else colors.onSurfaceFaint)
            }
        }
    }
}

@Composable
fun LoginScreen(container: AppContainer, server: SavedServer, message: String?) {
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(server.users.firstOrNull { it.id == server.lastUserId }?.name ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(message) }
    var publicUsers by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val api = remember(server) { container.sessions.apiFor(server.url) }

    LaunchedEffect(server.id) {
        publicUsers = container.sessions.publicUsers(server)
        runCatching { if (username.isBlank()) userFocus.requestFocus() else passwordFocus.requestFocus() }
    }

    fun signIn() {
        if (busy || username.isBlank()) return
        busy = true; error = null
        scope.launch {
            try {
                container.sessions.login(server, username, password)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (t: Throwable) {
                error = t.toAppError().userMessage
            } finally {
                busy = false
            }
        }
    }

    OnboardingFrame("Sign in to ${server.name}", server.url) {
        if (publicUsers.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                items(publicUsers, key = { it.id }) { user ->
                    FocusSurface(
                        onClick = {
                            username = user.name
                            if (!user.hasPassword) { password = ""; signIn() } else runCatching { passwordFocus.requestFocus() }
                        },
                        shape = CircleShape,
                        modifier = Modifier.size(88.dp),
                    ) {
                        Box(Modifier.fillMaxSize().clip(CircleShape).background(colors.surfaceRaised), contentAlignment = Alignment.Center) {
                            Text(user.name.take(1).uppercase(), style = AuroraType.title, color = colors.onBackground)
                            api.images.userAvatar(user.id, user.primaryImageTag, 176)?.let {
                                ArtworkImage(com.auroratv.jellyfin.Artwork(it, null), user.name, Modifier.fillMaxSize(), showPlaceholder = false)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
        }
        AuroraTextField(
            value = username, onValueChange = { username = it; error = null }, label = "Username",
            modifier = Modifier.fillMaxWidth().focusRequester(userFocus),
            onIme = { runCatching { passwordFocus.requestFocus() } },
        )
        Spacer(Modifier.height(14.dp))
        AuroraTextField(
            value = password, onValueChange = { password = it; error = null }, label = "Password", password = true,
            imeAction = ImeAction.Done, onIme = ::signIn,
            modifier = Modifier.fillMaxWidth().focusRequester(passwordFocus),
        )
        error?.let {
            Spacer(Modifier.height(12.dp))
            Text(it, style = AuroraType.body, color = colors.error, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            AuroraButton(if (busy) "Signing in…" else "Sign in", ::signIn, icon = Icons.Filled.Login, primary = true)
            AuroraButton("Change server", { container.sessions.showAddServer() })
        }
    }
}
