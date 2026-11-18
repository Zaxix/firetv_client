package com.auroratv.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Bumped whenever this device changes what the current profile has watched (playback stopped,
 * marked watched/unwatched, favourite toggled). Home refreshes Continue Watching, Next Up and the
 * recommendation rows when it changes, instead of on every visit.
 */
object WatchActivity {
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()
    fun changed() { _version.value++ }
}
