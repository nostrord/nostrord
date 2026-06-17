package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.searchInput
import org.nostr.nostrord.web.components.useEscClose
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface MemberManagementModalProps : Props {
    var groupId: String
    var onClose: () -> Unit
}

/**
 * Manage-members modal — real port of the Compose MemberManagementModal: search +
 * All/Admins/Members tabs over the live `groupMembers`/`groupAdmins`, with promote
 * (addUser roles=["admin"]) / demote (addUser roles=[]) / remove (removeUser).
 */
val MemberManagementModal =
    FC<MemberManagementModalProps> { props ->
        val repo = AppModule.nostrRepository
        val members = useStateFlow(repo.groupMembers)[props.groupId].orEmpty()
        val admins = useStateFlow(repo.groupAdmins)[props.groupId].orEmpty().toSet()
        val userMetadata = useStateFlow(repo.userMetadata)

        val (tab, setTab) = useState { "All" }
        val (query, setQuery) = useState { "" }

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (pubkey.take(8) + "…")
        }

        val filtered =
            members
                .filter {
                    when (tab) {
                        "Admins" -> it in admins
                        "Members" -> it !in admins
                        else -> true
                    }
                }
                .filter { query.isBlank() || nameOf(it).contains(query, ignoreCase = true) }

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
                        icon(Ic.Close)
                    }
                }

                searchInput(
                    placeholder = "Search members...",
                    value = query,
                    onChange = { setQuery(it) },
                    compact = true,
                )

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
                    if (filtered.isEmpty()) {
                        div {
                            className = ClassName("mod-empty")
                            +"No members found"
                        }
                    }
                    filtered.forEach { pubkey ->
                        val isAdmin = pubkey in admins
                        div {
                            key = pubkey
                            className = ClassName("mod-row")
                            WebAvatar {
                                url = userMetadata[pubkey]?.picture
                                seed = pubkey
                                name = nameOf(pubkey)
                                cls = "mod-avatar"
                            }
                            div {
                                className = ClassName("mod-name-wrap")
                                span {
                                    className = ClassName("mod-name")
                                    +nameOf(pubkey)
                                }
                                if (isAdmin) {
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
                                    onClick = {
                                        launchApp {
                                            repo.addUser(props.groupId, pubkey, if (isAdmin) emptyList() else listOf("admin"))
                                        }
                                    }
                                    +(if (isAdmin) "Demote" else "Promote")
                                }
                                button {
                                    className = ClassName("mod-btn danger")
                                    onClick = { launchApp { repo.removeUser(props.groupId, pubkey) } }
                                    +"Remove"
                                }
                            }
                        }
                    }
                }
            }
        }
    }
