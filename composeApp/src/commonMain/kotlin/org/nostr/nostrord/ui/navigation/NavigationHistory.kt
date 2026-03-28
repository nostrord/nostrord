package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.nostr.nostrord.ui.Screen

/**
 * A navigation entry storing both the screen and the relay that was active.
 */
data class NavEntry(val screen: Screen, val relayUrl: String)

/**
 * Browser-like navigation history with back/forward support.
 *
 * Maintains an ordered list of visited screens (with associated relay URLs)
 * and a cursor pointing to the current position. Navigating to a new screen
 * truncates any forward history. The history is capped at [MAX_HISTORY_SIZE] entries.
 */
class NavigationHistory(initialScreen: Screen, initialRelayUrl: String = "") {

    companion object {
        private const val MAX_HISTORY_SIZE = 50
    }

    private val history = mutableStateListOf(NavEntry(initialScreen, initialRelayUrl))
    private var cursorIndex by mutableIntStateOf(0)

    val currentScreen: Screen
        get() = history[cursorIndex].screen

    val currentRelayUrl: String
        get() = history[cursorIndex].relayUrl

    val canGoBack: Boolean by derivedStateOf { cursorIndex > 0 }

    val canGoForward: Boolean by derivedStateOf { cursorIndex < history.lastIndex }

    /**
     * Navigate to a new screen. Truncates forward history and appends.
     * Skips if [screen] and [relayUrl] are the same as the current entry.
     */
    fun navigate(screen: Screen, relayUrl: String = "") {
        val current = history[cursorIndex]
        if (screen == current.screen && relayUrl == current.relayUrl) return

        // Truncate forward history
        while (history.lastIndex > cursorIndex) {
            history.removeAt(history.lastIndex)
        }

        history.add(NavEntry(screen, relayUrl))
        cursorIndex = history.lastIndex

        // Cap history size by removing oldest entries
        while (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(0)
            cursorIndex--
        }
    }

    /**
     * Move back in history. Returns the new entry, or null if already at the start.
     */
    fun goBack(): NavEntry? {
        if (!canGoBack) return null
        cursorIndex--
        return history[cursorIndex]
    }

    /**
     * Move forward in history. Returns the new entry, or null if already at the end.
     */
    fun goForward(): NavEntry? {
        if (!canGoForward) return null
        cursorIndex++
        return history[cursorIndex]
    }

    /**
     * Prepend Home at the base of the history stack so back navigation
     * returns to Home instead of closing the app (e.g. after restore).
     */
    fun ensureHomeBase() {
        if (history.firstOrNull()?.screen !is Screen.Home) {
            history.add(0, NavEntry(Screen.Home, history.firstOrNull()?.relayUrl ?: ""))
            cursorIndex++
        }
    }
}
