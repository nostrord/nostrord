package org.nostr.nostrord.web.modals

import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/** Access-settings toggle row (Private / Closed) — mirrors Compose `AccessToggleRow`. */
internal fun ChildrenBuilder.accessToggle(
    icon: String,
    label: String,
    description: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    div {
        className = ClassName("access-row")
        onClick = { onToggle() }
        span {
            className = ClassName("access-icon")
            +icon
        }
        div {
            className = ClassName("access-text")
            div {
                className = ClassName("access-label")
                +label
            }
            div {
                className = ClassName("access-desc")
                +description
            }
        }
        div {
            className = ClassName(if (checked) "switch on" else "switch")
            div { className = ClassName("switch-thumb") }
        }
    }
}
