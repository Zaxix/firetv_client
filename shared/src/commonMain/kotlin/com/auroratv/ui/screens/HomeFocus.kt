package com.auroratv.ui.screens

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import kotlinx.coroutines.launch

/**
 * One place on Home that Up/Down can land on: the banner, the brand tiles or a row, in screen order.
 * [lazyIndex] is its item in Home's list; [canFocus] is false while it's a loading placeholder.
 */
internal class FocusStop(val key: String, val lazyIndex: Int, val canFocus: Boolean)

/**
 * Up and Down between Home's rows.
 *
 * Compose finds the next row by geometry, and Home's list only composes what's on screen plus a
 * look-ahead of about two screens' worth of items. Rows still loading (placeholders) and rows that
 * came back empty used to be part of that list: with a few of them between two real rows, the
 * search found nothing, and the shell took "nothing above" to mean "top of the screen" and moved
 * to the profile button (Down just stopped). Now empty rows aren't in the list at all, and when the
 * geometric search finds nothing, focus goes to the next row that has cards, scrolled into view
 * first if it isn't composed. Only Up from the top row (the banner) reaches the profile button.
 *
 * The same stops are used to put focus back on a nearby row when the focused card disappears
 * (its row reloaded or emptied) instead of leaving focus nowhere.
 */
internal class HomeRowFocus {
    private val requesters = HashMap<String, FocusRequester>()

    /** Requesting it focuses that stop (a row returns to its last card, else the first on screen). */
    fun requester(key: String): FocusRequester = requesters.getOrPut(key) { FocusRequester() }

    /** Uses [requester] (one the screen already has, like the banner's Play) for [key]. */
    fun register(key: String, requester: FocusRequester) { requesters[key] = requester }

    /** The stop that has (or last had) focus, and where it was in the list then. */
    var current: String? = null
        private set
    var currentIndex: Int = -1
        private set
    /** Whether anything on Home has focus. */
    var hasFocus = false
        internal set
    /** A move to a row that had to be scrolled on screen first is under way. */
    internal var moving = false

    fun focused(stop: String, lazyIndex: Int) {
        // CI only: the rows focus passes through (the emulator test walks Home down and back up).
        if (com.auroratv.ui.components.UiTrace.on && stop != current) com.auroratv.core.Log.i("HomeFocus", "row=$stop")
        current = stop; currentIndex = lazyIndex
    }

    /** Focuses [target]: scrolled into the list first if it isn't composed, then asked for focus. */
    suspend fun bring(target: FocusStop, list: LazyListState): Boolean {
        if (list.layoutInfo.visibleItemsInfo.none { it.index == target.lazyIndex }) list.scrollToItem(target.lazyIndex)
        repeat(4) {
            withFrameNanos { }
            runCatching { requester(target.key).requestFocus() }
            withFrameNanos { }
            if (current == target.key && hasFocus) return true
        }
        com.auroratv.core.Log.w("HomeFocus", "Couldn't focus ${target.key}")
        return false
    }

    companion object {
        /** The stop Up ([down] false) or Down reaches from [from], skipping placeholders; null at the edge. */
        fun next(stops: List<FocusStop>, from: String?, down: Boolean): FocusStop? {
            val at = stops.indexOfFirst { it.key == from }
            if (at < 0) return null
            return if (down) stops.drop(at + 1).firstOrNull { it.canFocus } else stops.take(at).lastOrNull { it.canFocus }
        }

        /**
         * Where focus goes when the focused card vanished: its own row if it still has cards, else the
         * closest row that does (the one now in its place first, then the one above).
         */
        fun nearest(stops: List<FocusStop>, key: String?, lazyIndex: Int): FocusStop? {
            stops.firstOrNull { it.key == key && it.canFocus }?.let { return it }
            val focusable = stops.filter { it.canFocus }
            if (focusable.isEmpty()) return null
            return focusable.minWith(compareBy<FocusStop> { kotlin.math.abs(it.lazyIndex - lazyIndex) }.thenBy { if (it.lazyIndex >= lazyIndex) 0 else 1 })
        }
    }
}

/**
 * Home's list: Up/Down that the geometric search can't place goes to the next row with cards
 * ([HomeRowFocus]); focus that vanishes with its card comes back to a nearby row, unless it left
 * on purpose ([mayRecover] false: the sidebar, the profile button, another screen).
 */
@Composable
internal fun Modifier.homeRowNavigation(
    focus: HomeRowFocus,
    list: LazyListState,
    stops: () -> List<FocusStop>,
    mayRecover: () -> Boolean,
): Modifier {
    val focusManager = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val currentStops by androidx.compose.runtime.rememberUpdatedState(stops)
    val recoverable by androidx.compose.runtime.rememberUpdatedState(mayRecover)
    // Home left (a detail page, the player): what it remembers about focus starts afresh when it's back,
    // so coming back isn't mistaken for the focused card vanishing.
    androidx.compose.runtime.DisposableEffect(focus) { onDispose { focus.hasFocus = false; focus.moving = false } }
    return remember(focus, list, focusManager, scope) {
        Modifier
            .onFocusChanged { state ->
                val lost = focus.hasFocus && !state.hasFocus
                focus.hasFocus = state.hasFocus
                if (lost) scope.launch {
                    // Give focus a frame or two to arrive wherever it was sent.
                    withFrameNanos { }; withFrameNanos { }
                    if (focus.hasFocus || focus.moving || !recoverable()) return@launch
                    val target = HomeRowFocus.nearest(currentStops(), focus.current, focus.currentIndex) ?: return@launch
                    com.auroratv.core.Log.i("HomeFocus", "Focused card went away; focus back on ${target.key}")
                    focus.moving = true
                    try { focus.bring(target, list) } finally { focus.moving = false }
                }
            }
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                val down = when (ev.key) {
                    Key.DirectionDown -> true
                    Key.DirectionUp -> false
                    else -> return@onKeyEvent false
                }
                if (focus.moving) return@onKeyEvent true // a move is being finished; this press is spent
                if (focusManager.moveFocus(if (down) FocusDirection.Down else FocusDirection.Up)) return@onKeyEvent true
                // Nothing composed in that direction: the next row with cards, if there is one. None
                // above: this is the top, and the shell takes Up to the profile button.
                val target = HomeRowFocus.next(currentStops(), focus.current, down) ?: return@onKeyEvent false
                focus.moving = true
                scope.launch { try { focus.bring(target, list) } finally { focus.moving = false } }
                true
            }
    }
}

/** Home's rows that take up space: loaded with cards, still loading, or failed (with Try Again). Empty ones aren't laid out. */
internal fun shownRails(rails: List<RailState>): List<RailState> = rails.filter { it.items?.isEmpty() != true || it.failed }

/** Home's stops in list order: [leading] items (banner, brand tiles), then the rows. */
internal fun homeStops(leading: List<String>, rails: List<RailState>?): List<FocusStop> = buildList {
    leading.forEachIndexed { i, k -> add(FocusStop(k, i, true)) }
    rails?.let { shownRails(it).forEachIndexed { i, r -> add(FocusStop(r.key, leading.size + i, r.items?.isNotEmpty() == true || r.failed)) } }
}
