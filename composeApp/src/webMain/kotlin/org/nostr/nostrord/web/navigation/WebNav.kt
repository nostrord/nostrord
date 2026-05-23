package org.nostr.nostrord.web.navigation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.ui.Screen

/**
 * Minimal in-memory router for the web target, reusing the shared `Screen` sealed class
 * (kept in commonMain) as the route model and the Flow bridge for reactivity — so no
 * extra dependency is needed.
 *
 * Deliberately not URL/History-backed yet: a Kotlin-native nav state covers the
 * authenticated SPA flow today. URL sync (deep links, back/forward) can layer on later
 * by pushing/reading `window.history` and emitting into [currentScreen].
 */
private val _currentScreen = MutableStateFlow<Screen>(Screen.Home)

val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

fun navigate(screen: Screen) {
    _currentScreen.value = screen
}
