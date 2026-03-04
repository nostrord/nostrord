package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import org.nostr.nostrord.ui.Screen

/**
 * Browser-like navigation history with back/forward support.
 *
 * Maintains an ordered list of visited screens and a cursor pointing
 * to the current position. Navigating to a new screen truncates any
 * forward history. The history is capped at [MAX_HISTORY_SIZE] entries.
 */
class NavigationHistory(initialScreen: Screen) {

    companion object {
        private const val MAX_HISTORY_SIZE = 50
    }

    private val history = mutableStateListOf(initialScreen)
    private var cursorIndex by mutableIntStateOf(0)

    val currentScreen: Screen
        get() = history[cursorIndex]

    val canGoBack: Boolean by derivedStateOf { cursorIndex > 0 }

    val canGoForward: Boolean by derivedStateOf { cursorIndex < history.lastIndex }

    /**
     * Navigate to a new screen. Truncates forward history and appends.
     * Skips if [screen] is the same as the current screen.
     */
    fun navigate(screen: Screen) {
        if (screen == currentScreen) return

        // Truncate forward history
        while (history.lastIndex > cursorIndex) {
            history.removeAt(history.lastIndex)
        }

        history.add(screen)
        cursorIndex = history.lastIndex

        // Cap history size by removing oldest entries
        while (history.size > MAX_HISTORY_SIZE) {
            history.removeAt(0)
            cursorIndex--
        }
    }

    /**
     * Move back in history. Returns the new current screen, or null if already at the start.
     */
    fun goBack(): Screen? {
        if (!canGoBack) return null
        cursorIndex--
        return currentScreen
    }

    /**
     * Move forward in history. Returns the new current screen, or null if already at the end.
     */
    fun goForward(): Screen? {
        if (!canGoForward) return null
        cursorIndex++
        return currentScreen
    }

    /**
     * Prepend Home at the base of the history stack so back navigation
     * returns to Home instead of closing the app (e.g. after restore).
     */
    fun ensureHomeBase() {
        if (history.firstOrNull() !is Screen.Home) {
            history.add(0, Screen.Home)
            cursorIndex++
        }
    }
}
