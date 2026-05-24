package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.WebAvatar
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface HomeScreenProps : Props {
    var onOpenGroup: (String) -> Unit
}

/**
 * Home / group picker — real port of the Compose HomeScreenDesktop: centered relay header,
 * My Groups / Other Groups filter chips, search, and a grid of group cards (icon, name,
 * about, access badges, Join / Joined / Request). Shown when a relay is active but no group
 * is open. Card click opens the group; the button joins it.
 */
val HomeScreen =
    FC<HomeScreenProps> { props ->
        val repo = AppModule.nostrRepository
        val currentRelay = useStateFlow(repo.currentRelayUrl)
        val relayMetadata = useStateFlow(repo.relayMetadata)
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        val joinedByRelay = useStateFlow(repo.joinedGroupsByRelay)
        val kind10009 = useStateFlow(repo.kind10009Relays)

        val isRelaySaved = currentRelay in kind10009
        val (optionsOpen, setOptionsOpen) = useState { false }
        val (confirmLeave, setConfirmLeave) = useState { false }

        val relayMeta = relayMetadata[currentRelay]
        val relayLabel = relayMeta?.name?.takeIf { it.isNotBlank() } ?: currentRelay.removePrefix("wss://").removePrefix("ws://")

        val groups = groupsByRelay[currentRelay].orEmpty()
        val joined = joinedByRelay[currentRelay].orEmpty()
        val myGroups = groups.filter { it.id in joined }
        val otherGroups = groups.filter { it.id !in joined }

        val (filter, setFilter) = useState { "Mine" }
        val (search, setSearch) = useState { "" }

        val base = if (filter == "Mine") myGroups else otherGroups
        val shown =
            base.filter {
                search.isBlank() ||
                    (it.name ?: it.id).contains(search, ignoreCase = true) ||
                    (it.about ?: "").contains(search, ignoreCase = true)
            }

        div {
            className = ClassName("home")
            div {
                className = ClassName("home-header")
                WebAvatar {
                    url = relayMeta?.icon
                    name = relayLabel
                    cls = "home-relay-icon"
                }
                div {
                    className = ClassName("home-title")
                    +relayLabel
                }
                div {
                    className = ClassName("home-subtitle")
                    +"Choose a group to join and start chatting."
                }

                // Relay options (add to / remove from the kind:10009 list)
                button {
                    className = ClassName("home-relay-options")
                    onClick = { setOptionsOpen(!optionsOpen) }
                    +"⋯"
                }
                if (optionsOpen) {
                    div {
                        className = ClassName("home-relay-menu-overlay")
                        onClick = { setOptionsOpen(false) }
                    }
                    div {
                        className = ClassName("home-relay-menu")
                        if (isRelaySaved) {
                            div {
                                className = ClassName("home-relay-menu-item danger")
                                onClick = {
                                    setOptionsOpen(false)
                                    setConfirmLeave(true)
                                }
                                +"Remove relay from your list"
                            }
                        } else {
                            div {
                                className = ClassName("home-relay-menu-item")
                                onClick = {
                                    setOptionsOpen(false)
                                    launchApp { repo.addRelay(currentRelay) }
                                }
                                +"Add relay to your list"
                            }
                        }
                    }
                }
            }

            div {
                className = ClassName("home-toolbar")
                div {
                    className = ClassName("home-filters")
                    homeFilter("My Groups", myGroups.size, filter == "Mine") { setFilter("Mine") }
                    homeFilter("Other Groups", otherGroups.size, filter == "Other") { setFilter("Other") }
                }
                input {
                    className = ClassName("home-search")
                    placeholder = "Search groups..."
                    value = search
                    onChange = { event -> setSearch(event.currentTarget.value) }
                }
            }

            div {
                className = ClassName("home-grid")
                if (shown.isEmpty()) {
                    div {
                        className = ClassName("home-empty")
                        +(if (search.isNotBlank()) "No groups match \"$search\"" else "No groups found")
                    }
                } else {
                    shown.forEach { group ->
                        pickGroupCard(
                            group = group,
                            isJoined = group.id in joined,
                            onOpen = { props.onOpenGroup(group.id) },
                            onJoin = { launchApp { repo.joinGroup(group.id) } },
                        )
                    }
                }
            }

            if (confirmLeave) {
                div {
                    className = ClassName("modal-overlay")
                    onClick = { setConfirmLeave(false) }
                    div {
                        className = ClassName("modal-card sm")
                        onClick = { it.stopPropagation() }
                        div {
                            className = ClassName("modal-title")
                            +"Leave relay?"
                        }
                        div {
                            className = ClassName("modal-subtitle tight")
                            +"$relayLabel will be removed from your list and you'll leave its groups. You can add it again anytime."
                        }
                        div {
                            className = ClassName("modal-footer")
                            button {
                                className = ClassName("btn-text")
                                onClick = { setConfirmLeave(false) }
                                +"Cancel"
                            }
                            button {
                                className = ClassName("btn-danger")
                                onClick = {
                                    setConfirmLeave(false)
                                    launchApp { repo.removeRelay(currentRelay) }
                                }
                                +"Leave relay"
                            }
                        }
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.homeFilter(label: String, count: Int, active: Boolean, onClick: () -> Unit) {
    button {
        className = ClassName(if (active) "home-filter active" else "home-filter")
        this.onClick = { onClick() }
        +(if (count > 0) "$label ($count)" else label)
    }
}

private fun ChildrenBuilder.pickGroupCard(
    group: GroupMetadata,
    isJoined: Boolean,
    onOpen: () -> Unit,
    onJoin: () -> Unit,
) {
    div {
        className = ClassName("pick-card")
        onClick = { onOpen() }
        WebAvatar {
            url = group.picture
            name = group.name ?: group.id
            cls = "pick-icon"
        }
        div {
            className = ClassName("pick-info")
            div {
                className = ClassName("pick-name")
                +(group.name ?: group.id)
            }
            if (!group.about.isNullOrBlank()) {
                div {
                    className = ClassName("pick-about")
                    +group.about
                }
            }
            div {
                className = ClassName("pick-badges")
                if (!group.isPublic) {
                    span {
                        className = ClassName("pick-badge private")
                        +"private"
                    }
                }
                if (!group.isOpen) {
                    span {
                        className = ClassName("pick-badge closed")
                        +"invite only"
                    }
                } else {
                    span {
                        className = ClassName("pick-badge open")
                        +"open"
                    }
                }
            }
        }
        button {
            className = ClassName(if (isJoined) "pick-btn joined" else "pick-btn")
            disabled = isJoined
            onClick = {
                it.stopPropagation()
                if (!isJoined) onJoin()
            }
            +(
                if (isJoined) {
                    "Joined"
                } else if (!group.isOpen) {
                    "Request"
                } else {
                    "Join"
                }
                )
        }
    }
}
