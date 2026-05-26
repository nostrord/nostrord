package org.nostr.nostrord.web

import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.notifications.installPlatformFocusListeners
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.installGlobalModalFocusTrap
import org.nostr.nostrord.web.screens.LoginScreen
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.useEffectOnce
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document

/**
 * Root React component. On mount it runs the real cold-start sequence — same as the Compose
 * AppViewModel: `nostrRepository.initialize()` (restores any persisted session and starts
 * relay/group discovery), with a 30s `forceInitialized()` fallback. The gate then mirrors
 * the app: loading until initialized, then shell (logged in) or login.
 */
val WebApp =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val initialized = useStateFlow(repo.isInitialized)
        val loggedIn = useStateFlow(repo.isLoggedIn)

        useEffectOnce {
            document
                .getElementById(ElementId("composeApplication"))
                ?.setAttribute("data-app-ready", "true")
            // Track tab focus so notifications/unread are suppressed while the app is visible
            // (mirrors native App.kt; without this the focus-gated dispatch never updates).
            installPlatformFocusListeners(AppModule.focusTracker)
            // Watch the DOM for .modal-card add/remove and trap keyboard focus inside the open
            // modal so Tab / Shift+Tab cycle through its controls instead of leaking back to the
            // page behind the backdrop. One install covers every current and future modal.
            installGlobalModalFocusTrap()
            // Drive the repository lifecycle from page visibility, mirroring native App.kt's
            // ON_PAUSE → onBackground / ON_RESUME → onForeground. The Compose web got this for
            // free via the shared Lifecycle observer; the React shell must wire it explicitly.
            // onBackground() flushes LiveCursorStore to storage — without it every reload
            // resumes group subscriptions from the default window instead of the last-seen
            // cursor, re-fetching history each time (the cache regression vs native/old web).
            document.asDynamic().addEventListener("visibilitychange") {
                if (document.asDynamic().hidden as Boolean) repo.onBackground() else repo.onForeground()
            }
            launchApp {
                withTimeoutOrNull(30_000) { repo.initialize() } ?: repo.forceInitialized()
            }
        }

        when {
            !initialized ->
                div {
                    className = ClassName("app-loading")
                    div { className = ClassName("app-spinner") }
                }
            loggedIn -> AppShell()
            else -> LoginScreen()
        }
    }
