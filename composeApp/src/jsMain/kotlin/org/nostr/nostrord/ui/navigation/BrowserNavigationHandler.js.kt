package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.window
import org.nostr.nostrord.ui.Screen
import org.w3c.dom.PopStateEvent

@Composable
actual fun BrowserNavigationHandler(
    currentScreen: Screen,
    onBack: () -> Unit,
    onForward: () -> Unit
) {
    // Keep callbacks up-to-date without re-registering the listener
    val currentOnBack by rememberUpdatedState(onBack)
    val currentOnForward by rememberUpdatedState(onForward)

    val depth = remember { mutableIntStateOf(0) }
    val skipNextPush = remember { mutableStateOf(false) }
    val isFirstScreen = remember { mutableStateOf(true) }

    // Register popstate listener once
    DisposableEffect(Unit) {
        // Set initial state with depth 0
        window.history.replaceState(0, "")

        val listener: (org.w3c.dom.events.Event) -> Unit = { event ->
            val popEvent = event as PopStateEvent
            val newDepth = (popEvent.state as? Number)?.toInt() ?: 0
            val oldDepth = depth.intValue

            if (newDepth != oldDepth) {
                skipNextPush.value = true
                depth.intValue = newDepth

                if (newDepth < oldDepth) {
                    currentOnBack()
                } else {
                    currentOnForward()
                }
            }
        }

        window.addEventListener("popstate", listener)
        onDispose {
            window.removeEventListener("popstate", listener)
        }
    }

    // Push state when screen changes from in-app navigation (not browser back/forward)
    LaunchedEffect(currentScreen) {
        if (isFirstScreen.value) {
            isFirstScreen.value = false
            return@LaunchedEffect
        }

        if (skipNextPush.value) {
            skipNextPush.value = false
            return@LaunchedEffect
        }

        val newDepth = depth.intValue + 1
        depth.intValue = newDepth
        window.history.pushState(newDepth, "")
    }
}
