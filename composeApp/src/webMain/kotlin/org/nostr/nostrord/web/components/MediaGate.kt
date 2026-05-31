package org.nostr.nostrord.web.components

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.web.bridge.useStateFlow
import react.ChildrenBuilder
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Reactive read of the Settings → Media "auto-load media" preference. When false,
 * inline chat media (image / video / YouTube) renders a [mediaGatePlaceholder]
 * instead of fetching, until the user taps it.
 *
 * This is a hook (calls [useStateFlow]); call it unconditionally at the top of a
 * media component, alongside that component's other hooks.
 */
fun useAutoLoadMedia(): Boolean = useStateFlow(AppModule.mediaSettings.autoLoadMedia)

/**
 * A clickable placeholder shown in place of [label] media when auto-load is off.
 * Tapping it calls [onLoad], which reveals the real media for that single item.
 */
fun ChildrenBuilder.mediaGatePlaceholder(label: String, onLoad: () -> Unit) {
    button {
        className = ClassName("msg-media-gate")
        onClick = {
            it.stopPropagation()
            onLoad()
        }
        span { className = ClassName("msg-media-gate-icon $label") }
        span {
            className = ClassName("msg-media-gate-label")
            +"Tap to load $label"
        }
    }
}
