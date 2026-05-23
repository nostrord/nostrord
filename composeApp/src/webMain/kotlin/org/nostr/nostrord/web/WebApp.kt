package org.nostr.nostrord.web

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.screens.LoginScreen
import react.FC
import react.Props
import react.useEffectOnce
import web.dom.ElementId
import web.dom.document

/**
 * Root React component for the web target.
 *
 * Auth gate: shows the React login when logged out, otherwise the persistent
 * [AppShell] (sidebar + content). All state comes straight from `AppModule` via the
 * bridge — no @JsExport. Hooks are called unconditionally (rules of hooks).
 */
val WebApp =
    FC<Props> {
        useEffectOnce {
            // Same hook index.html's loading shell watches: flips once React mounts so
            // the spinner overlay is removed.
            document
                .getElementById(ElementId("composeApplication"))
                ?.setAttribute("data-app-ready", "true")
        }

        val loggedIn = useStateFlow(AppModule.authManager.isLoggedIn)

        if (loggedIn) {
            AppShell()
        } else {
            LoginScreen()
        }
    }
