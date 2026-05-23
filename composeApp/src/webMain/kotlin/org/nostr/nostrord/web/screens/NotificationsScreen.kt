package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Notifications — renders `notificationHistoryStore.entries`, resolving the actor's name
 * from `userMetadata`. Mark-all-read / clear act on the shared store.
 */
val NotificationsScreen =
    FC<Props> {
        val entries = useStateFlow(AppModule.notificationHistoryStore.entries)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)

        div {
            className = ClassName("app-shell")
            h1 { +"Notifications" }

            div {
                className = ClassName("row-actions")
                button {
                    className = ClassName("secondary")
                    onClick = { launchApp { AppModule.notificationHistoryStore.markAllRead() } }
                    +"Mark all read"
                }
                button {
                    className = ClassName("secondary")
                    onClick = { launchApp { AppModule.notificationHistoryStore.clearHistory() } }
                    +"Clear"
                }
            }

            if (entries.isEmpty()) {
                p {
                    className = ClassName("muted")
                    +"No notifications yet."
                }
            } else {
                div {
                    className = ClassName("group-list")
                    entries.forEach { entry ->
                        val actor = userMetadata[entry.actorPubkey]
                        val actorName =
                            actor?.displayName?.takeIf { it.isNotBlank() }
                                ?: actor?.name?.takeIf { it.isNotBlank() }
                                ?: (entry.actorPubkey.take(8) + "…")
                        div {
                            key = entry.id
                            className = ClassName(if (entry.read) "notif-row" else "notif-row notif-unread")
                            span {
                                className = ClassName("group-name")
                                +(actorName + " · " + (entry.groupName ?: entry.groupId.take(8)))
                            }
                            span {
                                className = ClassName("group-about")
                                +entry.preview
                            }
                        }
                    }
                }
            }
        }
    }
