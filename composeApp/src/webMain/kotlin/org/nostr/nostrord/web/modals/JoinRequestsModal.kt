package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface JoinRequestsModalProps : Props {
    var groupId: String
    var onClose: () -> Unit
}

/**
 * Join-requests modal — real port of the Compose JoinRequestsModal: pending requesters
 * derived from the group's `messages` (kind:9021, minus current members and anyone whose
 * latest event is a leave kind:9022), with Approve (`addUser`) / Reject (`rejectJoinRequest`).
 */
val JoinRequestsModal =
    FC<JoinRequestsModalProps> { props ->
        val repo = AppModule.nostrRepository
        val msgs = useStateFlow(repo.messages)[props.groupId].orEmpty()
        val members = useStateFlow(repo.groupMembers)[props.groupId].orEmpty().toSet()
        val userMetadata = useStateFlow(repo.userMetadata)

        val lastLeave =
            msgs.filter { it.kind == 9022 }
                .groupBy { it.pubkey }
                .mapValues { (_, events) -> events.maxOf { it.createdAt } }
        // (pubkey, eventId) newest first
        val pending =
            msgs.filter { it.kind == 9021 && it.pubkey !in members }
                .filter { req -> lastLeave[req.pubkey].let { it == null || req.createdAt > it } }
                .distinctBy { it.pubkey }
                .sortedByDescending { it.createdAt }

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (pubkey.take(8) + "…")
        }

        useEscClose { props.onClose() }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +"Join Requests (${pending.size})"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                if (pending.isEmpty()) {
                    div {
                        className = ClassName("mod-empty")
                        +"No pending requests"
                    }
                } else {
                    div {
                        className = ClassName("mod-list")
                        pending.forEach { req ->
                            div {
                                key = req.id
                                className = ClassName("mod-row")
                                WebAvatar {
                                    url = userMetadata[req.pubkey]?.picture
                                    seed = req.pubkey
                                    name = nameOf(req.pubkey)
                                    cls = "mod-avatar"
                                }
                                span {
                                    className = ClassName("mod-name")
                                    +nameOf(req.pubkey)
                                }
                                div {
                                    className = ClassName("mod-actions")
                                    button {
                                        className = ClassName("mod-btn primary")
                                        onClick = { launchApp { repo.addUser(props.groupId, req.pubkey) } }
                                        +"Approve"
                                    }
                                    button {
                                        className = ClassName("mod-btn danger")
                                        onClick = { launchApp { repo.rejectJoinRequest(props.groupId, req.id) } }
                                        +"Reject"
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
