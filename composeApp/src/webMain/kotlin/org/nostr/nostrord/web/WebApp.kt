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
import react.useEffect
import react.useEffectOnce
import web.dom.ElementId
import web.dom.document

/**
 * Root React component. On mount it runs the real cold-start sequence — same as the Compose
 * AppViewModel: `nostrRepository.initialize()` (restores any persisted session and starts
 * relay/group discovery), with a 30s `forceInitialized()` fallback.
 *
 * The HTML `#loading-shell` (index.html) stays visible until `data-app-ready` is set on
 * `#composeApplication`. We set that only AFTER `repo.isInitialized` flips true — that way
 * the shell bridges the whole cold-start (HTML parse → bundle load → repo.initialize) and
 * hands off directly to Login / AppShell, instead of the canvas-era pattern of two spinners
 * in a row (HTML shell removed on React mount, then a separate `.app-loading` rendered while
 * the repo finished initializing).
 */
val WebApp =
    FC<Props> {
        val repo = AppModule.nostrRepository
        val initialized = useStateFlow(repo.isInitialized)
        val loggedIn = useStateFlow(repo.isLoggedIn)
        val verifyingBunker = useStateFlow(repo.isBunkerVerifying)

        useEffectOnce {
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

        // Hand off the HTML loading shell to the React app once initialization
        // completes. Setting this earlier (on mount) would expose the user to a
        // visible second spinner while the repo finishes booting.
        useEffect(initialized) {
            if (initialized) {
                document
                    .getElementById(ElementId("composeApplication"))
                    ?.setAttribute("data-app-ready", "true")
            }
        }

        when {
            // Render nothing until initialized; the HTML shell holds the screen
            // and only fades out once data-app-ready is set above.
            !initialized -> null
            loggedIn -> AppShell()
            // A bunker signer is still being (re)connected on cold start: hold the loading
            // shell instead of flashing LoginScreen during the async handshake. This is the
            // only restore path that completes after isInitialized (local/NIP-07 restore
            // flips isLoggedIn before initialize() returns). isBunkerVerifying flips false
            // when the signer connects (-> loggedIn -> AppShell) or fails (-> LoginScreen),
            // so unlike gating on persisted-account existence it can never strand the user
            // on a permanent spinner after logout or a failed restore.
            verifyingBunker -> null
            else -> LoginScreen()
        }
    }
