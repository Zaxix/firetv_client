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
import androidx.compose.foundation.clickable
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onInterceptKeyBeforeSoftKeyboard
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.auroratv.core.toAppError
import com.auroratv.data.SavedServer
import com.auroratv.jellyfin.model.UserDto
import com.auroratv.ui.AppContainer
import com.auroratv.ui.LocalContainer
import com.auroratv.ui.components.ArtworkImage
import com.auroratv.ui.components.AuroraButton
import com.auroratv.ui.components.FocusSurface
import com.auroratv.ui.theme.Aurora
import com.auroratv.ui.theme.AuroraType
import kotlinx.coroutines.launch

/**
 * A text box that behaves on a TV: moving onto it only highlights it (no keyboard popping up and
 * trapping the D-pad). Select/click starts typing and opens the keyboard; Back, Up/Down or the
 * keyboard's action key stop typing, close the keyboard and leave you on the box. A hardware
 * keyboard (or `adb input text`) can type straight into the highlighted box, and Enter submits.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
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
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val platform = LocalContainer.current.platform
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var boxFocused by remember { mutableStateOf(false) }
    // After Escape/Enter the highlight returns to the box; don't start typing again until focus leaves it.
    var holdAutoEdit by remember { mutableStateOf(false) }
    var heldWhileFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }
    val boxFocus = remember { FocusRequester() }
    val boxClicks = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    // Keys can arrive faster than the screen recomposes (hardware keyboards, pasted text), so typing
    // into the highlighted box builds on what we last sent, not on a possibly stale [value].
    val typed = remember { TypedText(value) }
    if (value != typed.sent) { typed.text = value; typed.sent = value }
    fun emit(text: String) { typed.text = text; typed.sent = text; onValueChange(text) }

    fun closeKeyboard() { keyboard?.hide(); platform.hideKeyboard() }
    // CI only (--ez traceUi true): what the field does with focus and the keyboard.
    fun trace(what: String) { if (com.auroratv.ui.components.UiTrace.on) com.auroratv.core.Log.i("AuroraTextField", "\"$label\": $what") }
    fun startEditing(handOverNow: Boolean = true) {
        trace("start typing")
        editing = true
        // The highlighted box holds focus and is about to leave the screen. Hand focus to the real field in the same moment:
        // if focus is dropped first it falls to the first thing on screen, which on the Settings page is the list of sections
        // (that switches the page away, and the keyboard never opens).
        if (handOverNow) runCatching { fieldFocus.requestFocus() }
        scope.launch {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { fieldFocus.requestFocus() }
            keyboard?.show()
        }
    }
    var fieldFocused by remember { mutableStateOf(false) }
    fun stopEditing(refocus: Boolean = true) {
        trace("stop typing (refocus=$refocus)")
        closeKeyboard()
        holdAutoEdit = true
        // Focus goes straight from the field to the highlighted box, which never leaves the screen, and
        // only then does the field stop being focusable. It used to be the other way round (the box was
        // added back a frame later): the field dropped focus first, focus was nowhere, and the app put it
        // on the first thing on screen, which on the Settings page is the "Appearance" section, so the
        // page switched and what was typed was gone.
        if (refocus) runCatching { boxFocus.requestFocus() }
        editing = false
    }
    fun isBack(ev: androidx.compose.ui.input.key.KeyEvent) = ev.key == Key.Back || ev.key == Key.Escape

    Box(modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { if (it.length != value.length) trace("${it.length} character(s)"); onValueChange(it) },
            label = { Text(label) },
            singleLine = true,
            visualTransformation = if (password) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboardType, imeAction = imeAction, autoCorrectEnabled = false),
            // If the action moved focus on (username → password), leave it there; otherwise back to this box.
            keyboardActions = KeyboardActions(onAny = { onIme(); stopEditing(refocus = fieldFocused) }),
            textStyle = AuroraType.bodyStrong.copy(color = colors.onBackground),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = colors.focusRing,
                unfocusedBorderColor = if (boxFocused) colors.focusRing else colors.divider,
                focusedLabelColor = colors.onBackground,
                unfocusedLabelColor = if (boxFocused) colors.onBackground else colors.onSurfaceFaint,
                cursorColor = colors.onBackground,
            ),
            modifier = Modifier.fillMaxWidth()
                .focusRequester(fieldFocus)
                .focusProperties { canFocus = editing }
                .onFocusChanged {
                    if (it.isFocused != fieldFocused) trace("field focused=${it.isFocused} editing=$editing")
                    fieldFocused = it.isFocused; if (!it.isFocused && editing) { editing = false; closeKeyboard() }
                }
                // Back reaches us before the keyboard does, so one press closes it and stops typing.
                .onInterceptKeyBeforeSoftKeyboard { ev ->
                    if (editing && isBack(ev)) { if (ev.type == KeyEventType.KeyUp) stopEditing(); true } else false
                }
                .onPreviewKeyEvent { ev ->
                    when {
                        !editing -> false
                        isBack(ev) -> { if (ev.type == KeyEventType.KeyUp) stopEditing(); true }
                        ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionUp -> { closeKeyboard(); focusManager.moveFocus(FocusDirection.Up); true }
                        ev.type == KeyEventType.KeyDown && ev.key == Key.DirectionDown -> { closeKeyboard(); focusManager.moveFocus(FocusDirection.Down); true }
                        else -> false
                    }
                },
        )
        // The D-pad stop: highlights the box; Select (or a click) starts typing. It stays on screen and
        // focusable while typing too, so focus always has somewhere to come back to:
        // the field hands focus over to it, and it hands focus to the field (see stopEditing).
        Box(
                Modifier.matchParentSize()
                    .focusRequester(boxFocus)
                    .onFocusChanged {
                        if (it.isFocused != boxFocused) trace("box focused=${it.isFocused}")
                        boxFocused = it.isFocused
                        // With a computer keyboard there's no on-screen keyboard to get stuck in: type straight away.
                        when {
                            it.isFocused && holdAutoEdit -> heldWhileFocused = true
                            it.isFocused && platform.typesOnFocus -> startEditing(handOverNow = false)
                            !it.isFocused && heldWhileFocused -> { holdAutoEdit = false; heldWhileFocused = false }
                        }
                    }
                    .onPreviewKeyEvent { ev ->
                        if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        val code = ev.utf16CodePoint
                        when {
                            ev.key == Key.Enter || ev.key == Key.NumPadEnter -> {
                                if (typed.text.isNotBlank()) onIme() else startEditing(); true
                            }
                            ev.key == Key.Backspace -> { if (typed.text.isNotEmpty()) emit(typed.text.dropLast(1)); true }
                            !ev.isCtrlPressed && !ev.isAltPressed && code >= 32 && code != 127 -> {
                                emit(typed.text + code.toChar()); true
                            }
                            else -> false
                        }
                    }
                    // Its click handler is also what makes it focusable, so it stays on while typing (and does nothing then).
                    .clickable(
                        interactionSource = boxClicks,
                        indication = null,
                        onClick = { if (!editing) startEditing() },
                    )
            )
    }
}

/** What [AuroraTextField] last typed (plain fields: read and written on the UI thread between frames). */
private class TypedText(initial: String) {
    var text: String = initial
    var sent: String = initial
}

@Composable
private fun OnboardingFrame(title: String, subtitle: String, content: @Composable () -> Unit) {
    val colors = Aurora.colors
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        com.auroratv.ui.components.FrostedField(com.auroratv.ui.components.BRAND_FROST)
        Column(
            Modifier.widthIn(max = 620.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            com.auroratv.ui.components.AppLogoImage(Modifier.size(96.dp))
            Spacer(Modifier.height(12.dp))
            Text(com.auroratv.AppInfo.NAME, style = AuroraType.hero, color = colors.onBackground)
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
    val keyboard = LocalSoftwareKeyboardController.current

    fun connect() {
        if (busy || address.isBlank()) return
        // Close the on-screen keyboard: on Android TV it otherwise keeps capturing the remote's D-pad.
        keyboard?.hide(); container.platform.hideKeyboard()
        busy = true; error = null
        scope.launch {
            try {
                val (url, info) = container.sessions.discover(address)
                container.sessions.addServer(url, info)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (t: Throwable) {
                error = connectionHelp(t.toAppError(), address, container.platform.vpnActive())
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
fun LoginScreen(container: AppContainer, server: SavedServer, message: String?, prefillUser: String? = null, canCancel: Boolean = false) {
    val colors = Aurora.colors
    val scope = rememberCoroutineScope()
    var username by remember { mutableStateOf(prefillUser ?: server.users.firstOrNull { it.id == server.lastUserId }?.name ?: "") }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(message) }
    var publicUsers by remember { mutableStateOf<List<UserDto>>(emptyList()) }
    val userFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }
    val api = remember(server) { container.sessions.apiFor(server.url) }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(server.id) {
        publicUsers = container.sessions.publicUsers(server)
        runCatching { if (username.isBlank()) userFocus.requestFocus() else passwordFocus.requestFocus() }
    }

    fun signIn() {
        if (busy || username.isBlank()) return
        keyboard?.hide(); container.platform.hideKeyboard()
        busy = true; error = null
        scope.launch {
            try {
                container.sessions.login(server, username, password)
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (t: Throwable) {
                error = connectionHelp(t.toAppError(), server.url, container.platform.vpnActive())
            } finally {
                busy = false
            }
        }
    }

    // From "Add profile" (or a signed-out profile), Back returns to "Who's watching?".
    val backModifier = if (canCancel) Modifier.onKeyEvent { ev ->
        if (ev.type == KeyEventType.KeyUp && (ev.key == Key.Back || ev.key == Key.Escape)) { container.sessions.cancelLogin(); true } else false
    } else Modifier
    Box(backModifier) {
    OnboardingFrame(if (canCancel && prefillUser.isNullOrEmpty()) "Add a profile" else "Sign in to ${server.name}", if (canCancel && prefillUser.isNullOrEmpty()) "Sign in with another ${server.name} account" else server.url) {
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
            if (canCancel) AuroraButton("Cancel", { container.sessions.cancelLogin() })
            AuroraButton(if (canCancel) "Other server" else "Change server", { container.sessions.showAddServer() })
        }
    }
    }
}

/**
 * The error to show when a server can't be reached, with VPN advice when one is on: the usual
 * reasons are a VPN app that won't let the TV reach its own home network, or the server's
 * home-network address being used where only its VPN address works (or the other way round).
 */
internal fun connectionHelp(error: com.auroratv.core.AppError, address: String, vpnOn: Boolean): String {
    val unreachable = error is com.auroratv.core.AppError.Unreachable || error is com.auroratv.core.AppError.Timeout
    if (!unreachable || !vpnOn) return error.userMessage
    val host = address.substringAfter("://").substringBefore('/').substringBeforeLast(':').removePrefix("[").removeSuffix("]")
    val homeAddress = Regex("""^(10|192\.168|172\.(1[6-9]|2\d|3[01]))\.""").containsMatchIn(host)
    return if (homeAddress) {
        "Can't reach $host while a VPN is on. In your VPN app, allow local network (LAN) access or exclude Sharkfin " +
            "(split tunnelling), or enter the server's VPN address instead (for Tailscale, its 100.x address or MagicDNS name)."
    } else {
        "Can't reach the server through your VPN. Check the VPN is connected and that the server is on it " +
            "(for Tailscale: same tailnet, address 100.x or name.ts.net, port 8096)."
    }
}
