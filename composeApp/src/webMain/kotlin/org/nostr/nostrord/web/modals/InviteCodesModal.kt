package org.nostr.nostrord.web.modals

import kotlinx.browser.window
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface InviteCodesModalProps : Props {
    var groupId: String
    var onClose: () -> Unit
}

private fun copyToClipboard(text: String) {
    val clip = window.navigator.asDynamic().clipboard
    if (clip != null) clip.writeText(text)
}

/**
 * Invite-codes modal — real port of the Compose InviteCodesModal (admin): active codes
 * derived from the group's `messages` (kind:9009 minus revoked kind:9005), create via
 * `createInviteCode`, revoke via `revokeInviteCode`, copy code / URL to the clipboard.
 */
val InviteCodesModal =
    FC<InviteCodesModalProps> { props ->
        val repo = AppModule.nostrRepository
        val msgs = useStateFlow(repo.messages)[props.groupId].orEmpty()
        val relayUrl = useStateFlow(repo.currentRelayUrl)
        val (busy, setBusy) = useState { false }

        val revoked =
            msgs.filter { it.kind == 9005 }
                .flatMap { m -> m.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) } }
                .toSet()
        // code -> eventId, newest first
        val codes =
            msgs.filter { it.kind == 9009 && it.id !in revoked }
                .mapNotNull { m ->
                    val code = m.tags.firstOrNull { it.firstOrNull() == "code" }?.getOrNull(1) ?: return@mapNotNull null
                    Triple(code, m.id, m.createdAt)
                }
                .sortedByDescending { it.third }

        fun inviteUrl(code: String): String {
            val relay = relayUrl.removePrefix("wss://").removePrefix("ws://")
            return "https://nostrord.com/open/?relay=$relay&group=${props.groupId}&code=$code"
        }

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
                            +"Invite Codes"
                        }
                        div {
                            className = ClassName("modal-subtitle")
                            +"This group requires an invite code to join."
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        +"✕"
                    }
                }

                button {
                    className = ClassName("btn-primary block")
                    disabled = busy
                    onClick = {
                        setBusy(true)
                        launchApp {
                            repo.createInviteCode(props.groupId)
                            setBusy(false)
                        }
                    }
                    +(if (busy) "Creating…" else "Create Invite Code")
                }

                div {
                    className = ClassName("access-section-title")
                    +"ACTIVE CODES (${codes.size})"
                }
                div {
                    className = ClassName("mod-list")
                    if (codes.isEmpty()) {
                        div {
                            className = ClassName("mod-empty")
                            +"No active invite codes"
                        }
                    }
                    codes.forEach { (code, eventId, _) ->
                        div {
                            key = eventId
                            className = ClassName("mod-row")
                            span {
                                className = ClassName("mod-code")
                                +code
                            }
                            div {
                                className = ClassName("mod-actions")
                                button {
                                    className = ClassName("mod-btn")
                                    onClick = { copyToClipboard(inviteUrl(code)) }
                                    +"Copy URL"
                                }
                                button {
                                    className = ClassName("mod-btn")
                                    onClick = { copyToClipboard(code) }
                                    +"Copy code"
                                }
                                button {
                                    className = ClassName("mod-btn danger")
                                    onClick = { launchApp { repo.revokeInviteCode(props.groupId, eventId) } }
                                    +"Revoke"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
