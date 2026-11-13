package com.auroratv.ui.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import com.auroratv.jellyfin.model.BaseItemDto

/** Top-level destinations in the side navigation. */
enum class Section(val label: String) {
    Home("Home"), Movies("Movies"), Shows("TV Shows"), Continue("Continue Watching"),
    Favorites("Favorites"), Search("Search"), Libraries("Libraries"), Settings("Settings")
}

sealed interface Route {
    data class Root(val section: Section) : Route
    data class Library(val parentId: String?, val title: String, val itemTypes: List<String>, val collectionType: String?) : Route
    data class Detail(val itemId: String, val preview: BaseItemDto? = null) : Route
    data class Player(val itemId: String, val startFromBeginning: Boolean = false) : Route
}

/**
 * One back-stack entry. Screen state objects ("models") live here so returning to a screen restores
 * its data and scroll position, and [focus] restores the exact card that was focused.
 */
class BackStackEntry(val route: Route) {
    val models = mutableMapOf<String, Any>()
    val focus = FocusMemory()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> model(key: String, create: () -> T): T = models.getOrPut(key, create) as T
}

/** Remembers which item had focus on a screen and can request it again after navigating back. */
class FocusMemory {
    var lastKey: String? = null
    private val requesters = mutableMapOf<String, FocusRequester>()

    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }
    fun forget(key: String) { if (key != lastKey) requesters.remove(key) }

    /** Tries to focus the remembered item; returns false when it isn't composed. */
    fun restore(): Boolean {
        val key = lastKey ?: return false
        val r = requesters[key] ?: return false
        return runCatching { r.requestFocus(); true }.getOrDefault(false)
    }
}

class Navigator(initial: Route) {
    private val stack = mutableListOf(BackStackEntry(initial))
    var current by mutableStateOf(stack.last())
        private set
    /** True after a pop, so the revealed screen restores focus instead of using its default. */
    var restoringFocus by mutableStateOf(false)
        private set

    val canGoBack get() = stack.size > 1

    fun push(route: Route) {
        stack += BackStackEntry(route)
        restoringFocus = false
        current = stack.last()
    }

    /** Replaces the whole stack with a top-level section (side-nav selection). */
    fun selectSection(section: Section) {
        val root = stack.first()
        if (root.route == Route.Root(section) && stack.size == 1) return
        stack.clear()
        stack += if (root.route == Route.Root(section)) root else BackStackEntry(Route.Root(section))
        restoringFocus = false
        current = stack.last()
    }

    fun pop(): Boolean {
        if (stack.size <= 1) return false
        stack.removeAt(stack.lastIndex)
        restoringFocus = true
        current = stack.last()
        return true
    }

    /** Replaces the top entry (e.g. player → next episode player). */
    fun replace(route: Route) {
        stack[stack.lastIndex] = BackStackEntry(route)
        current = stack.last()
    }

    fun focusRestored() { restoringFocus = false }

    val currentSection: Section
        get() = (stack.first().route as? Route.Root)?.section ?: Section.Home
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }
val LocalEntry = staticCompositionLocalOf<BackStackEntry?> { null }

/**
 * Requests initial focus for a screen: the remembered card when returning, otherwise [fallback].
 * Retries for a few frames because lazy lists compose their items asynchronously.
 */
@Composable
fun ScreenFocus(entry: BackStackEntry, navigator: Navigator, fallback: FocusRequester?, ready: Boolean = true) {
    LaunchedEffect(entry, ready) {
        if (!ready) return@LaunchedEffect
        repeat(8) {
            withFrameNanos { }
            if (navigator.restoringFocus && entry.focus.restore()) { navigator.focusRestored(); return@LaunchedEffect }
            if (!navigator.restoringFocus && fallback != null && runCatching { fallback.requestFocus(); true }.getOrDefault(false)) return@LaunchedEffect
        }
        navigator.focusRestored()
    }
}

@Composable
fun rememberNavigator(initial: Route): Navigator = remember { Navigator(initial) }
