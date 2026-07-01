package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface AddGroupModalProps : Props {
    var onClose: () -> Unit
    var onJoin: () -> Unit
    var onCreate: () -> Unit
}

/**
 * Add-group chooser (prototype GroupModals 'chooser', opened by the rail "+"): pick
 * between joining an existing group or creating a new one. Mirrors the Compose
 * ui/screens/group/components/AddGroupModal.
 */
val AddGroupModal =
    FC<AddGroupModalProps> { props ->
        useEscClose { props.onClose() }
        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card sm")
                onClick = { it.stopPropagation() }
                div {
                    className = ClassName("modal-title")
                    +"Add group"
                }
                div {
                    className = ClassName("modal-subtitle tight")
                    +"How do you want to start?"
                }
                div {
                    className = ClassName("choice-cards")
                    choiceCard(
                        ic = Ic.Link,
                        title = "Join a group",
                        description = "Use an invite link, naddr, or ID to join a group that already exists.",
                        onClick = props.onJoin,
                    )
                    choiceCard(
                        ic = Ic.People,
                        title = "Create a group",
                        description = "Create a moderated group from scratch, hosted on a groups relay.",
                        onClick = props.onCreate,
                    )
                }
            }
        }
    }

/** Centered icon + title + description card (prototype ChoiceCard). */
private fun ChildrenBuilder.choiceCard(
    ic: Ic,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    button {
        className = ClassName("choice-card")
        this.onClick = { onClick() }
        span {
            className = ClassName("choice-card-icon")
            icon(ic)
        }
        span {
            className = ClassName("choice-card-title")
            +title
        }
        span {
            className = ClassName("choice-card-desc")
            +description
        }
    }
}
