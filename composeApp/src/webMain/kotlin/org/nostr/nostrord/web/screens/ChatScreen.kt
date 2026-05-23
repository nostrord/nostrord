package org.nostr.nostrord.web.screens

import org.nostr.nostrord.web.mock.Mock
import org.nostr.nostrord.web.mock.MockGroup
import org.nostr.nostrord.web.mock.MockMember
import org.nostr.nostrord.web.mock.MockMessage
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName

external interface ChatScreenProps : Props {
    var group: MockGroup
}

/**
 * Chat view — layout-first React port of the Compose GroupScreenDesktop: header +
 * grouped messages + composer in the main column, members sidebar on the right (collapses
 * to a drawer on narrow screens). Mock conversation; sending/joining is stubbed.
 */
val ChatScreen =
    FC<ChatScreenProps> { props ->
        val group = props.group
        val (draft, setDraft) = useState { "" }
        val (membersOpen, setMembersOpen) = useState { false }

        div {
            className = ClassName(if (membersOpen) "chat members-open" else "chat")

            // Main column: header + messages + composer
            div {
                className = ClassName("chat-main")

                // Header
                div {
                    className = ClassName("chat-header")
                    div {
                        className = ClassName("chat-header-title")
                        div {
                            className = ClassName("avatar-tile chat-header-icon avatar-fallback")
                            +group.name.take(1).uppercase()
                        }
                        div {
                            className = ClassName("chat-header-meta")
                            div {
                                className = ClassName("chat-header-name")
                                +group.name
                            }
                            if (!group.about.isNullOrBlank()) {
                                div {
                                    className = ClassName("chat-header-about")
                                    +group.about
                                }
                            }
                        }
                    }
                    button {
                        className = ClassName("chat-members-btn")
                        onClick = { setMembersOpen(!membersOpen) }
                        +"👥"
                    }
                }

                // Messages
                div {
                    className = ClassName("chat-messages")
                    div {
                        className = ClassName("chat-date")
                        span { +Mock.sampleDate }
                    }
                    systemEvent("fiatjaf joined the group")
                    Mock.sampleMessages.forEach { message ->
                        messageRow(message)
                    }
                }

                // Composer
                div {
                    className = ClassName("composer")
                    button {
                        className = ClassName("composer-btn")
                        +"＋"
                    }
                    input {
                        className = ClassName("composer-input")
                        placeholder = "Message ${group.name}"
                        value = draft
                        onChange = { event -> setDraft(event.currentTarget.value) }
                    }
                    button {
                        className = ClassName("composer-btn")
                        +"😊"
                    }
                    button {
                        className = ClassName(if (draft.isNotBlank()) "composer-send active" else "composer-send")
                        disabled = draft.isBlank()
                        onClick = { setDraft("") }
                        +"➤"
                    }
                }
            }

            // Member sidebar
            div {
                className = ClassName("member-backdrop")
                onClick = { setMembersOpen(false) }
            }
            div {
                className = ClassName("member-sidebar")
                div {
                    className = ClassName("member-header")
                    +"Members — ${Mock.sampleMembers.size}"
                }
                div {
                    className = ClassName("member-search")
                    input {
                        className = ClassName("member-search-input")
                        placeholder = "Search members"
                    }
                }
                div {
                    className = ClassName("member-scroll")
                    val online = Mock.sampleMembers.filter { it.online }
                    val offline = Mock.sampleMembers.filter { !it.online }
                    if (online.isNotEmpty()) {
                        memberSection("Online", online.size)
                        online.forEach { memberRow(it, online = true) }
                    }
                    if (offline.isNotEmpty()) {
                        memberSection("Offline", offline.size)
                        offline.forEach { memberRow(it, online = false) }
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.messageRow(message: MockMessage) {
    div {
        className = ClassName(if (message.firstInGroup) "msg first" else "msg grouped")
        div {
            className = ClassName("msg-gutter")
            if (message.firstInGroup) {
                div {
                    className = ClassName("avatar-tile msg-avatar avatar-fallback")
                    +message.author.take(1).uppercase()
                }
            } else {
                span {
                    className = ClassName("msg-hover-time")
                    +message.time
                }
            }
        }
        div {
            className = ClassName("msg-body")
            if (message.firstInGroup) {
                div {
                    className = ClassName("msg-meta")
                    span {
                        className = ClassName("msg-author")
                        +message.author
                    }
                    if (message.admin) {
                        span {
                            className = ClassName("msg-admin")
                            +"ADMIN"
                        }
                    }
                    span {
                        className = ClassName("msg-time")
                        +message.time
                    }
                }
            }
            div {
                className = ClassName("msg-text")
                +message.content
            }
        }
    }
}

private fun ChildrenBuilder.systemEvent(text: String) {
    div {
        className = ClassName("system-event")
        span {
            className = ClassName("system-event-icon")
            +"→"
        }
        span { +text }
    }
}

private fun ChildrenBuilder.memberSection(title: String, count: Int) {
    div {
        className = ClassName("member-section")
        +"$title — $count"
    }
}

private fun ChildrenBuilder.memberRow(member: MockMember, online: Boolean) {
    div {
        className = ClassName("member-row")
        div {
            className = ClassName("member-avatar-wrap")
            div {
                className = ClassName(if (online) "avatar-tile member-avatar avatar-fallback" else "avatar-tile member-avatar avatar-fallback dimmed")
                +member.name.take(1).uppercase()
            }
            span {
                className = ClassName(if (online) "member-dot online" else "member-dot")
            }
        }
        span {
            className = ClassName(if (online) "member-name" else "member-name dimmed")
            +member.name
        }
        if (member.admin) {
            span {
                className = ClassName("member-admin")
                +"ADMIN"
            }
        }
    }
}
