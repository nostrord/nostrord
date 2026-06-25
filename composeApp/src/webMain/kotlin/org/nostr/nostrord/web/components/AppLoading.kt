package org.nostr.nostrord.web.components

import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.p
import web.cssom.ClassName

/**
 * Full-screen bootstrap / loading screen (brand spinner over a label): the React analogue of the
 * index.html `#loading-shell`, theme-aware via the injected `--color-*` vars. Shown while the
 * active account's group list resolves, so a switch routes to Home or onboarding without flashing
 * either, and mirrors the Compose [LoadingScreen].
 */
val AppLoading = FC<Props> {
    div {
        className = ClassName("app-loading")
        div { className = ClassName("app-loading-spinner") }
        p { +"Loading Nostrord…" }
    }
}
