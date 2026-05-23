package org.nostr.nostrord.web.components

import org.nostr.nostrord.network.GroupMetadata
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface GroupRowProps : Props {
    var group: GroupMetadata
    var actionLabel: String?
    var selected: Boolean?
    var onActivate: () -> Unit
}

/**
 * One group row — avatar (picture or initial fallback), name + about. When [actionLabel]
 * is null the whole row is clickable (open); otherwise it shows an action chip (e.g.
 * "Join"). Shared by the sidebar (joined groups) and the discover view (available).
 */
val GroupRow =
    FC<GroupRowProps> { props ->
        val group = props.group
        div {
            className = ClassName(if (props.selected == true) "group-row selected" else "group-row")
            if (props.actionLabel == null) {
                onClick = { props.onActivate() }
            }

            val picture = group.picture
            if (!picture.isNullOrBlank()) {
                img {
                    className = ClassName("group-avatar")
                    src = picture
                    alt = group.name ?: group.id
                }
            } else {
                div {
                    className = ClassName("group-avatar group-avatar-fallback")
                    +(group.name ?: group.id).take(1).uppercase()
                }
            }

            div {
                className = ClassName("group-meta")
                span {
                    className = ClassName("group-name")
                    +(group.name ?: group.id.take(12))
                }
                group.about?.takeIf { it.isNotBlank() }?.let { about ->
                    span {
                        className = ClassName("group-about")
                        +about
                    }
                }
            }

            props.actionLabel?.let { label ->
                button {
                    className = ClassName("chip")
                    onClick = { props.onActivate() }
                    +label
                }
            }
        }
    }
