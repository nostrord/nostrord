package org.nostr.nostrord.web.modals

import org.nostr.nostrord.web.mock.Mock
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface MemberManagementModalProps : Props {
    var onClose: () -> Unit
}

/**
 * Manage-members modal — layout-first React port of the Compose MemberManagementModal:
 * search + All/Admins/Members tabs + per-member promote/demote/remove actions. Mock data;
 * actions are stubbed.
 */
val MemberManagementModal =
    FC<MemberManagementModalProps> { props ->
        val (tab, setTab) = useState { "All" }

        val members =
            when (tab) {
                "Admins" -> Mock.sampleMembers.filter { it.admin }
                "Members" -> Mock.sampleMembers.filter { !it.admin }
                else -> Mock.sampleMembers
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
                            +"Manage Members"
                        }
                        div {
                            className = ClassName("modal-subtitle")
                            +"Promote, demote, or remove group members."
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        +"✕"
                    }
                }

                input {
                    className = ClassName("modal-input")
                    placeholder = "Search members..."
                }

                div {
                    className = ClassName("mod-tabs")
                    listOf("All", "Admins", "Members").forEach { label ->
                        button {
                            key = label
                            className = ClassName(if (label == tab) "mod-tab selected" else "mod-tab")
                            onClick = { setTab(label) }
                            +label
                        }
                    }
                }

                div {
                    className = ClassName("mod-list")
                    members.forEach { member ->
                        div {
                            key = member.name
                            className = ClassName("mod-row")
                            div {
                                className = ClassName("avatar-tile mod-avatar avatar-fallback")
                                +member.name.take(1).uppercase()
                            }
                            div {
                                className = ClassName("mod-name-wrap")
                                span {
                                    className = ClassName("mod-name")
                                    +member.name
                                }
                                if (member.admin) {
                                    span {
                                        className = ClassName("member-admin")
                                        +"ADMIN"
                                    }
                                }
                            }
                            div {
                                className = ClassName("mod-actions")
                                button {
                                    className = ClassName("mod-btn")
                                    +(if (member.admin) "Demote" else "Promote")
                                }
                                button {
                                    className = ClassName("mod-btn danger")
                                    +"Remove"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
