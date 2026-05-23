package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.mock.Mock
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface InviteCodesModalProps : Props {
    var onClose: () -> Unit
}

/**
 * Invite-codes modal — layout-first React port of the Compose InviteCodesModal (admin
 * view): create a code + the active-codes list with Copy URL / Copy code / Revoke. Mock
 * data; create/copy/revoke are stubbed.
 */
val InviteCodesModal =
    FC<InviteCodesModalProps> { props ->
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
                    +"Create Invite Code"
                }

                div {
                    className = ClassName("access-section-title")
                    +"ACTIVE CODES (${Mock.sampleInviteCodes.size})"
                }
                div {
                    className = ClassName("mod-list")
                    Mock.sampleInviteCodes.forEach { code ->
                        div {
                            key = code
                            className = ClassName("mod-row")
                            span {
                                className = ClassName("mod-code")
                                +code
                            }
                            div {
                                className = ClassName("mod-actions")
                                button {
                                    className = ClassName("mod-btn")
                                    +"Copy URL"
                                }
                                button {
                                    className = ClassName("mod-btn")
                                    +"Copy code"
                                }
                                button {
                                    className = ClassName("mod-btn danger")
                                    +"Revoke"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
