package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.navigation.navigate
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

private fun relayHost(url: String): String = url.removePrefix("wss://").removePrefix("ws://").trimEnd('/')

/**
 * Home content shown when no group is selected — mirrors the Compose desktop
 * "pick a group to get started": a relay header + a list of group cards (join /
 * open). Relay switching lives in the server rail; this is the discovery surface.
 */
val DiscoverScreen =
    FC<Props> {
        val currentRelay = useStateFlow(AppModule.nostrRepository.currentRelayUrl)
        val groupsByRelay = useStateFlow(AppModule.nostrRepository.groupsByRelay)
        val joinedByRelay = useStateFlow(AppModule.nostrRepository.joinedGroupsByRelay)
        val loadingRelays = useStateFlow(AppModule.nostrRepository.loadingRelays)
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)

        if (currentRelay.isBlank()) {
            div {
                className = ClassName("home-welcome")
                div {
                    className = ClassName("home-welcome-inner")
                    h1 { +"Nostrord" }
                    p { +"Connect to a NIP-29 relay with the + in the left rail to get started." }
                }
            }
            return@FC
        }

        val joinedIds = joinedByRelay.values.flatten().toSet()
        val groups =
            groupsByRelay.values
                .flatten()
                .distinctBy { it.id }
                .sortedBy { (it.name ?: it.id).lowercase() }

        div {
            className = ClassName("home-content")

            div {
                className = ClassName("home-header")
                val icon = relayMetadata[currentRelay]?.icon
                if (!icon.isNullOrBlank()) {
                    img {
                        className = ClassName("home-relay-icon")
                        src = icon
                        alt = ""
                    }
                } else {
                    div {
                        className = ClassName("home-relay-icon avatar-fallback")
                        +relayHost(currentRelay).take(1).uppercase()
                    }
                }
                div {
                    className = ClassName("home-title")
                    +relayHost(currentRelay)
                }
                div {
                    className = ClassName("home-subtitle")
                    +"Pick a group to get started"
                }
            }

            when {
                groups.isEmpty() && currentRelay in loadingRelays ->
                    p {
                        className = ClassName("muted")
                        +"Loading groups…"
                    }
                groups.isEmpty() ->
                    p {
                        className = ClassName("muted")
                        +"No groups on this relay yet. Create one from the sidebar."
                    }
                else ->
                    div {
                        className = ClassName("pick-list")
                        groups.forEach { group ->
                            val joined = group.id in joinedIds
                            div {
                                key = group.id
                                className = ClassName("pick-card")
                                onClick = {
                                    if (joined) {
                                        navigate(Screen.Group(group.id, group.name))
                                    } else {
                                        launchApp { AppModule.nostrRepository.joinGroup(group.id) }
                                    }
                                }

                                val picture = group.picture
                                if (!picture.isNullOrBlank()) {
                                    img {
                                        className = ClassName("pick-avatar")
                                        src = picture
                                        alt = ""
                                    }
                                } else {
                                    div {
                                        className = ClassName("pick-avatar avatar-fallback")
                                        +(group.name ?: group.id).take(1).uppercase()
                                    }
                                }

                                div {
                                    className = ClassName("pick-meta")
                                    div {
                                        className = ClassName("pick-name")
                                        +(group.name ?: group.id.take(16))
                                    }
                                    group.about?.takeIf { it.isNotBlank() }?.let { about ->
                                        div {
                                            className = ClassName("pick-about")
                                            +about
                                        }
                                    }
                                }

                                span {
                                    className = ClassName(
                                        if (joined) {
                                            "pick-action open"
                                        } else if (!group.isOpen) {
                                            "pick-action join"
                                        } else {
                                            "pick-action join"
                                        },
                                    )
                                    +(
                                        if (joined) {
                                            "Open"
                                        } else if (!group.isOpen) {
                                            "Request"
                                        } else {
                                            "Join"
                                        }
                                        )
                                }
                            }
                        }
                    }
            }
        }
    }
