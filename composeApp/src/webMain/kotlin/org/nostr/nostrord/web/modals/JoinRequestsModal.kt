package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.mock.Mock
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

external interface JoinRequestsModalProps : Props {
    var onClose: () -> Unit
}

/**
 * Join-requests modal — layout-first React port of the Compose JoinRequestsModal: pending
 * requesters with Approve / Reject, or an empty state. Mock data; actions are stubbed.
 */
val JoinRequestsModal =
    FC<JoinRequestsModalProps> { props ->
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
                            +"Join Requests (${Mock.sampleJoinRequests.size})"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        +"✕"
                    }
                }

                if (Mock.sampleJoinRequests.isEmpty()) {
                    div {
                        className = ClassName("mod-empty")
                        +"No pending requests"
                    }
                } else {
                    div {
                        className = ClassName("mod-list")
                        Mock.sampleJoinRequests.forEach { name ->
                            div {
                                key = name
                                className = ClassName("mod-row")
                                div {
                                    className = ClassName("avatar-tile mod-avatar avatar-fallback")
                                    +name.take(1).uppercase()
                                }
                                span {
                                    className = ClassName("mod-name")
                                    +name
                                }
                                div {
                                    className = ClassName("mod-actions")
                                    button {
                                        className = ClassName("mod-btn primary")
                                        +"Approve"
                                    }
                                    button {
                                        className = ClassName("mod-btn danger")
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
