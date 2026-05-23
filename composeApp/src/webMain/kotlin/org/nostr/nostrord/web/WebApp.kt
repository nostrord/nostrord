package org.nostr.nostrord.web

import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.mock.mockSession
import org.nostr.nostrord.web.screens.LoginScreen
import react.FC
import react.Props
import react.useEffectOnce
import web.dom.ElementId
import web.dom.document

/**
 * Root React component (layout-first rebuild). Mock auth gate so login → shell can be
 * validated without any API: login actions flip a mock session flag. Real API wiring
 * comes after the layouts are approved.
 */
val WebApp =
    FC<Props> {
        useEffectOnce {
            document
                .getElementById(ElementId("composeApplication"))
                ?.setAttribute("data-app-ready", "true")
        }
        val loggedIn = useStateFlow(mockSession)
        if (loggedIn) {
            AppShell()
        } else {
            LoginScreen()
        }
    }
