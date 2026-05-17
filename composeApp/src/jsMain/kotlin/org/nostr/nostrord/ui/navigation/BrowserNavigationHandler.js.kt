package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.window
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.utils.toRelayUrl

private fun buildUrlQuery(
    relayUrl: String,
    screen: Screen,
): String {
    // Notifications is cross-relay — keep it out of the relay namespace so the
    // URL stays meaningful (and refresh-stable) even with no relay context.
    if (screen is Screen.Notifications) return "?view=notifications"
    if (relayUrl.isBlank()) return window.location.pathname
    val relay =
        relayUrl
            .removePrefix("wss://")
            .removePrefix("ws://")
    return when (screen) {
        is Screen.Group -> "?relay=$relay&group=${screen.groupId}"
        else -> "?relay=$relay"
    }
}

/**
 * Parse relay and group from a URL search string like "?relay=host&group=id".
 */
private data class UrlParams(
    val relayUrl: String,
    val groupId: String?,
    val inviteCode: String?,
    val viewNotifications: Boolean,
)

private fun parseUrlQuery(search: String): UrlParams {
    val params =
        search.removePrefix("?").split("&").associate { param ->
            val idx = param.indexOf("=")
            if (idx >= 0) {
                param.substring(0, idx) to param.substring(idx + 1)
            } else {
                param to ""
            }
        }
    val relay = params["relay"]?.takeIf { it.isNotBlank() } ?: ""
    val relayUrl = relay.toRelayUrl()
    val groupId = params["group"]?.takeIf { it.isNotBlank() }
    val inviteCode = params["code"]?.takeIf { it.isNotBlank() }
    val viewNotifications = params["view"] == "notifications"
    return UrlParams(relayUrl, groupId, inviteCode, viewNotifications)
}

@Composable
actual fun BrowserNavigationHandler(
    currentScreen: Screen,
    selectedRelayUrl: String,
    onUrlNavigation: (relayUrl: String, groupId: String?, inviteCode: String?, viewNotifications: Boolean) -> Unit,
) {
    val currentOnUrlNavigation by rememberUpdatedState(onUrlNavigation)

    val skipNextPush = remember { mutableStateOf(false) }
    val isFirstRender = remember { mutableStateOf(true) }

    // Track last pushed URL to avoid duplicate pushes
    val lastPushedUrl = remember { mutableStateOf("") }

    // Register popstate listener once
    DisposableEffect(Unit) {
        // Preserve the current browser URL if relay isn't resolved yet (e.g. during login).
        // This prevents deep link URLs like /?relay=X&group=Y from being wiped to /.
        if (selectedRelayUrl.isNotBlank()) {
            val initialUrl = buildUrlQuery(selectedRelayUrl, currentScreen)
            window.history.replaceState(null, "", initialUrl)
            lastPushedUrl.value = initialUrl
        } else {
            lastPushedUrl.value = window.location.search.ifBlank { window.location.pathname }
        }

        val listener: (org.w3c.dom.events.Event) -> Unit = { _ ->
            // Parse the URL the browser navigated to
            val parsed = parseUrlQuery(window.location.search)
            skipNextPush.value = true
            lastPushedUrl.value = window.location.search.ifBlank { window.location.pathname }
            // `view=notifications` is allowed to fire without a relay — the
            // Notifications screen is cross-relay and the app keeps its
            // previously selected relay in the sidebar.
            if (parsed.relayUrl.isNotBlank() || parsed.viewNotifications) {
                currentOnUrlNavigation(parsed.relayUrl, parsed.groupId, parsed.inviteCode, parsed.viewNotifications)
            }
        }

        window.addEventListener("popstate", listener)
        onDispose {
            window.removeEventListener("popstate", listener)
        }
    }

    // Push state when screen or relay changes from in-app navigation
    LaunchedEffect(currentScreen, selectedRelayUrl) {
        if (isFirstRender.value) {
            isFirstRender.value = false
            return@LaunchedEffect
        }

        val url = buildUrlQuery(selectedRelayUrl, currentScreen)

        if (skipNextPush.value) {
            // Browser navigated (back/forward) — just sync URL without pushing
            skipNextPush.value = false
            window.history.replaceState(null, "", url)
            lastPushedUrl.value = url
            return@LaunchedEffect
        }

        // Avoid duplicate push if URL hasn't changed
        if (url == lastPushedUrl.value) return@LaunchedEffect

        window.history.pushState(null, "", url)
        lastPushedUrl.value = url
    }
}
