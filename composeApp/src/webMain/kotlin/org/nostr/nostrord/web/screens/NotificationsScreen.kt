package org.nostr.nostrord.web.screens

import org.nostr.nostrord.web.mock.Mock
import org.nostr.nostrord.web.mock.MockNotification
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Notifications — layout-first React port of the Compose NotificationsScreen. Rendered in
 * the shell content (rail stays, groups sidebar hidden). Header (Mark all as read / Clear
 * all) + a list of entries (avatar + type badge, author + action, preview, group · time;
 * unread rows get a primary accent). Mock data; actions are stubbed.
 */
val NotificationsScreen =
    FC<Props> {
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
                        +"Mark all as read"
                    }
                    button {
                        className = ClassName("notif-action-btn")
                        +"Clear all"
                    }
                }
            }
            div {
                className = ClassName("notif-list")
                if (Mock.sampleNotifications.isEmpty()) {
                    div {
                        className = ClassName("notif-empty")
                        div {
                            className = ClassName("notif-empty-icon")
                            +"🔔"
                        }
                        div { +"No notifications yet" }
                    }
                } else {
                    Mock.sampleNotifications.forEach { notification ->
                        notifItem(notification)
                    }
                }
            }
        }
    }

private fun typeLabel(type: String): String =
    when (type) {
        "reply" -> "replied to your message"
        "mention" -> "mentioned you"
        "reaction" -> "reacted to your message"
        else -> "sent a message"
    }

private fun typeGlyph(type: String, preview: String): String =
    when (type) {
        "reply" -> "↩"
        "mention" -> "@"
        "reaction" -> preview
        else -> "💬"
    }

private fun ChildrenBuilder.notifItem(notification: MockNotification) {
    div {
        className = ClassName(if (notification.read) "notif-item" else "notif-item unread")
        div { className = ClassName("notif-accent") }
        div {
            className = ClassName("notif-main")
            div {
                className = ClassName("notif-avatar-wrap")
                div {
                    className = ClassName("avatar-tile notif-avatar avatar-fallback")
                    +notification.actor.take(1).uppercase()
                }
                span {
                    className = ClassName("notif-badge ${notification.type}")
                    +typeGlyph(notification.type, notification.preview)
                }
            }
            div {
                className = ClassName("notif-body")
                div {
                    className = ClassName("notif-head")
                    span {
                        className = ClassName("notif-actor")
                        +notification.actor
                    }
                    +" "
                    span {
                        className = ClassName("notif-label")
                        +typeLabel(notification.type)
                    }
                }
                div {
                    className = ClassName("notif-preview")
                    +notification.preview
                }
                div {
                    className = ClassName("notif-context")
                    div {
                        className = ClassName("avatar-tile notif-context-icon avatar-fallback")
                        +notification.group.take(1).uppercase()
                    }
                    span {
                        className = ClassName("notif-context-name")
                        +notification.group
                    }
                    span {
                        className = ClassName("notif-dot")
                        +"·"
                    }
                    span {
                        className = ClassName("notif-time")
                        +notification.time
                    }
                }
            }
        }
    }
}
