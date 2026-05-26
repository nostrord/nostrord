package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface ManageChildrenModalProps : Props {
    var onClose: () -> Unit
}

// Placeholder data — the subgroups draft feature's submit/listing isn't wired yet.
private val approvedChildren = listOf("Design" to "g2", "Random" to "g3")
private val pendingClaims = listOf("Bots" to "g8", "Memes" to "g9")

/**
 * Manage-children modal — layout-first React port of the Compose ManageChildrenModal
 * (admin, draft subgroups): add a child by ID, a "Closed children" toggle, the bilaterally
 * approved children list (Pin / Remove), and pending claims (Add). Mock data; stubbed.
 */
val ManageChildrenModal =
    FC<ManageChildrenModalProps> { props ->
        val (closed, setClosed) = useState { false }

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
                            +"Manage Children"
                        }
                        div {
                            className = ClassName("modal-subtitle")
                            +(
                                "Children fall into bilaterally approved, admin-attested, and pending claims. " +
                                    "Only bilateral approval removes the ⚠ warning for other clients."
                                )
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                div {
                    className = ClassName("field-label")
                    +"Add child by ID"
                }
                div {
                    className = ClassName("share-field")
                    input {
                        className = ClassName("modal-input flush")
                        placeholder = "child-group-id"
                    }
                    button {
                        className = ClassName("btn-primary")
                        +"Add"
                    }
                }

                accessToggle(
                    Ic.Block,
                    "Closed children",
                    "Only children listed below are accepted; all other parent claims are rejected.",
                    closed,
                ) { setClosed(!closed) }

                div {
                    className = ClassName("access-section-title")
                    +"APPROVED CHILDREN, BILATERAL (${approvedChildren.size})"
                }
                div {
                    className = ClassName("mod-list")
                    approvedChildren.forEach { (name, id) ->
                        childRow(name, id, approved = true)
                    }
                }

                div {
                    className = ClassName("access-section-title")
                    +"PENDING CLAIMS (${pendingClaims.size})"
                }
                div {
                    className = ClassName("mod-list")
                    pendingClaims.forEach { (name, id) ->
                        childRow(name, id, approved = false)
                    }
                }

                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-text")
                        onClick = { props.onClose() }
                        +"Cancel"
                    }
                    button {
                        className = ClassName("btn-primary")
                        onClick = { props.onClose() }
                        +"Save Changes"
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.childRow(name: String, id: String, approved: Boolean) {
    div {
        className = ClassName("mod-row")
        div {
            className = ClassName("avatar-tile mod-avatar avatar-fallback")
            +name.take(1).uppercase()
        }
        div {
            className = ClassName("mod-name-wrap")
            span {
                className = ClassName("mod-name")
                +name
            }
        }
        div {
            className = ClassName("mod-actions")
            if (approved) {
                button {
                    className = ClassName("mod-btn")
                    +"Pin"
                }
                button {
                    className = ClassName("mod-btn danger")
                    +"Remove"
                }
            } else {
                button {
                    className = ClassName("mod-btn primary")
                    +"Add"
                }
            }
        }
    }
}
