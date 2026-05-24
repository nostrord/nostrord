package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.notifications.NotificationEntry
import org.nostr.nostrord.notifications.NotificationType
import org.nostr.nostrord.utils.formatTimestamp
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.WebAvatar
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import react.useEffect
import web.cssom.ClassName

external interface NotificationsScreenProps : Props {
    var onOpen: (relayUrl: String, groupId: String) -> Unit
}

/**
 * Notifications — real port of the Compose NotificationsScreen: the live
 * `notificationHistoryStore.entries` (avatar + type badge, actor + action, preview,
 * group · time; unread accent), with Mark all as read / Clear all. Clicking an entry marks
 * it read and opens its group. Rendered in the shell content (rail stays, sidebar hidden).
 */
val NotificationsScreen =
    FC<NotificationsScreenProps> { props ->
        val store = AppModule.notificationHistoryStore
        val entries = useStateFlow(store.entries)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)

        useEffect(entries.size) {
            val actors = entries.map { it.actorPubkey }.toSet()
            if (actors.isNotEmpty()) launchApp { AppModule.nostrRepository.requestUserMetadata(actors) }
        }

        div {
            className = ClassName("notif-screen")
            div {
                className = ClassName("notif-header")
                div {
                    className = ClassName("notif-title")
                    +"Notifications"
                }
                div {
                    className = ClassName("notif-actions")
                    button {
                        className = ClassName("notif-action-btn")
                        onClick = { store.markAllRead() }
                        +"Mark all as read"
                    }
                    button {
                        className = ClassName("notif-action-btn")
                        onClick = { store.clearHistory() }
                        +"Clear all"
                    }
                }
            }
            div {
                className = ClassName("notif-list")
                if (entries.isEmpty()) {
                    div {
                        className = ClassName("notif-empty")
                        div {
                            className = ClassName("notif-empty-icon")
                            +"🔔"
                        }
                        div { +"No notifications yet" }
                    }
                } else {
                    entries.forEach { entry ->
                        notifItem(entry, userMetadata[entry.actorPubkey]) {
                            store.markRead(entry.id)
                            props.onOpen(entry.relayUrl, entry.groupId)
                        }
                    }
                }
            }
        }
    }

private fun typeClass(type: NotificationType): String = when (type) {
    NotificationType.REPLY -> "reply"
    NotificationType.MENTION -> "mention"
    NotificationType.REACTION -> "reaction"
    NotificationType.MESSAGE -> "message"
}

private fun typeLabel(type: NotificationType): String = when (type) {
    NotificationType.REPLY -> "replied to your message"
    NotificationType.MENTION -> "mentioned you"
    NotificationType.REACTION -> "reacted to your message"
    NotificationType.MESSAGE -> "sent a message"
}

private fun typeGlyph(entry: NotificationEntry): String = when (entry.type) {
    NotificationType.REPLY -> "↩"
    NotificationType.MENTION -> "@"
    NotificationType.REACTION -> entry.emoji?.takeIf { it.isNotBlank() } ?: "+"
    NotificationType.MESSAGE -> "💬"
}

private fun ChildrenBuilder.notifItem(entry: NotificationEntry, actorMeta: UserMetadata?, onSelect: () -> Unit) {
    val actor =
        actorMeta?.displayName?.takeIf { it.isNotBlank() }
            ?: actorMeta?.name?.takeIf { it.isNotBlank() }
            ?: (entry.actorPubkey.take(8) + "…")
    val group = entry.groupName?.takeIf { it.isNotBlank() } ?: entry.groupId.take(8)
    val preview = if (entry.type == NotificationType.REACTION) (entry.emoji ?: entry.preview) else entry.preview

    div {
        className = ClassName(if (entry.read) "notif-item" else "notif-item unread")
        onClick = { onSelect() }
        div { className = ClassName("notif-accent") }
        div {
            className = ClassName("notif-main")
            div {
                className = ClassName("notif-avatar-wrap")
                WebAvatar {
                    url = actorMeta?.picture
                    name = actor
                    cls = "notif-avatar"
                }
                span {
                    className = ClassName("notif-badge ${typeClass(entry.type)}")
                    +typeGlyph(entry)
                }
            }
            div {
                className = ClassName("notif-body")
                div {
                    className = ClassName("notif-head")
                    span {
                        className = ClassName("notif-actor")
                        +actor
                    }
                    +" "
                    span {
                        className = ClassName("notif-label")
                        +typeLabel(entry.type)
                    }
                }
                div {
                    className = ClassName("notif-preview")
                    +preview
                }
                div {
                    className = ClassName("notif-context")
                    div {
                        className = ClassName("avatar-tile notif-context-icon avatar-fallback")
                        +group.take(1).uppercase()
                    }
                    span {
                        className = ClassName("notif-context-name")
                        +group
                    }
                    span {
                        className = ClassName("notif-dot")
                        +"·"
                    }
                    span {
                        className = ClassName("notif-time")
                        +formatTimestamp(entry.createdAt)
                    }
                }
            }
        }
    }
}
