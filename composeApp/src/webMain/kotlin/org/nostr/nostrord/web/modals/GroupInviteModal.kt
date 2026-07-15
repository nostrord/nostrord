package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import web.cssom.ClassName

external interface GroupInviteModalProps : Props {
    var groupId: String
    var groupName: String
    var picture: String?
    var relayHost: String
    var actorLabel: String?
    var onAccept: () -> Unit
    var onDecline: () -> Unit
    var onDismiss: () -> Unit
}

/**
 * The pending-invite prompt (web counterpart of GroupScreen's GroupInviteDialog), in the
 * DM invite card's visual language (`.gic-*`): "GROUP INVITE" eyebrow + group avatar/name
 * + relay host, one line saying who invited (or a neutral "You were added" when the actor
 * is unknown or the relay itself), and Decline / Accept. Backdrop/Esc = decide later.
 */
val GroupInviteModal =
    FC<GroupInviteModalProps> { props ->
        useEscClose { props.onDismiss() }
        div {
            className = ClassName("modal-overlay")
            onClick = { props.onDismiss() }
            div {
                className = ClassName("modal-card sm")
                onClick = { it.stopPropagation() }
                div {
                    className = ClassName("gic-head")
                    WebAvatar {
                        url = props.picture
                        seed = props.groupId
                        kind = AvatarKind.GROUP
                        name = props.groupName
                        cls = "gic-avatar"
                    }
                    div {
                        className = ClassName("gic-titles")
                        div {
                            className = ClassName("gic-eyebrow")
                            +"Group invite"
                        }
                        div {
                            className = ClassName("gic-name")
                            +props.groupName
                        }
                        div {
                            className = ClassName("mod-npub")
                            +props.relayHost
                        }
                    }
                }
                div {
                    className = ClassName("modal-subtitle tight")
                    +(
                        props.actorLabel?.let { "$it invited you to this group. Accept to add it to your groups, or decline to leave." }
                            ?: "You were added to this group. Accept to add it to your groups, or decline to leave."
                        )
                }
                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-text")
                        onClick = { props.onDecline() }
                        +"Decline"
                    }
                    button {
                        className = ClassName("btn-primary")
                        onClick = { props.onAccept() }
                        +"Accept"
                    }
                }
            }
        }
    }
