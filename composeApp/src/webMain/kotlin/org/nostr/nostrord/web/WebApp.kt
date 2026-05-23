package org.nostr.nostrord.web

import org.nostr.nostrord.web.screens.LoginScreen
import react.FC
import react.Props
import react.useEffectOnce
import web.dom.ElementId
import web.dom.document

/**
 * Root React component (layout-first rebuild). For now it just shows the login screen so
 * the layout can be validated against the Compose desktop app, before any API wiring.
 */
val WebApp =
    FC<Props> {
        useEffectOnce {
            document
                .getElementById(ElementId("composeApplication"))
                ?.setAttribute("data-app-ready", "true")
        }
        LoginScreen()
    }
