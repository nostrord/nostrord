@file:OptIn(ExperimentalWasmJsInterop::class)
package org.nostr.nostrord.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlin.js.ExperimentalWasmJsInterop
import org.nostr.nostrord.ui.Screen

@JsFun("(depth) => window.history.replaceState(depth, '')")
private external fun jsHistoryReplaceState(depth: Int)

@JsFun("(depth) => window.history.pushState(depth, '')")
private external fun jsHistoryPushState(depth: Int)

/**
 * Registers a popstate listener that calls back with the state depth (Int).
 * Returns a function that removes the listener when called.
 */
@JsFun("""(callback) => {
    const listener = (event) => {
        const depth = (typeof event.state === 'number') ? event.state : 0;
        callback(depth);
    };
    window.addEventListener('popstate', listener);
    return () => window.removeEventListener('popstate', listener);
}""")
private external fun jsAddPopStateListener(callback: (Int) -> Unit): () -> Unit

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
        jsHistoryReplaceState(0)

        val removeListener = jsAddPopStateListener { newDepth ->
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

        onDispose {
            removeListener()
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
        jsHistoryPushState(newDepth)
    }
}
