package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.navigation.navigate
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.dom.ElementId
import web.dom.document

external interface GroupScreenProps : Props {
    var groupId: String
    var groupName: String?
}

private val QUICK_EMOJIS = listOf("👍", "❤️", "😂", "🎉")

private val urlRegex = Regex("""https?://\S+""")

private fun isImageUrl(url: String): Boolean {
    val u = url.lowercase().substringBefore('?').substringBefore('#')
    return u.endsWith(".jpg") ||
        u.endsWith(".jpeg") ||
        u.endsWith(".png") ||
        u.endsWith(".gif") ||
        u.endsWith(".webp") ||
        u.endsWith(".svg") ||
        u.endsWith(".avif")
}

/** Split message content into text runs, inline images and links. */
private fun ChildrenBuilder.renderMessageContent(content: String) {
    var last = 0
    for (match in urlRegex.findAll(content)) {
        if (match.range.first > last) {
            +content.substring(last, match.range.first)
        }
        val url = match.value
        if (isImageUrl(url)) {
            img {
                className = ClassName("chat-image")
                src = url
                alt = ""
            }
        } else {
            a {
                className = ClassName("chat-link")
                href = url
                +url
            }
        }
        last = match.range.last + 1
    }
    if (last < content.length) {
        +content.substring(last)
    }
}

/**
 * Group chat — message list (with inline images/links, reactions and reply context) +
 * composer, all driven by shared Kotlin via the bridge: `messages`, `reactions`,
 * `userMetadata`; actions `sendMessage`/`sendReaction`/`leaveGroup`.
 */
val GroupScreen =
    FC<GroupScreenProps> { props ->
        val messagesByGroup = useStateFlow(AppModule.nostrRepository.messages)
        val userMetadata = useStateFlow(AppModule.nostrRepository.userMetadata)
        val reactions = useStateFlow(AppModule.nostrRepository.reactions)
        val (input, setInput) = useState { "" }
        val (sending, setSending) = useState { false }
        val (replyingTo, setReplyingTo) = useState<NostrMessage?> { null }

        val messages = (messagesByGroup[props.groupId] ?: emptyList()).sortedBy { it.createdAt }
        val messagesById = messages.associateBy { it.id }

        fun authorName(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (pubkey.take(8) + "…")
        }

        fun replyParentId(message: NostrMessage): String? = message.tags.find { it.size >= 2 && it[0] == "q" && it[1].length == 64 }?.get(1)

        useEffect(props.groupId) {
            AppModule.nostrRepository.requestGroupMessages(props.groupId)
        }
        useEffect(messages.size) {
            val authors = messages.map { it.pubkey }.toSet()
            if (authors.isNotEmpty()) AppModule.nostrRepository.requestUserMetadata(authors)
        }
        useEffect(messages.size) {
            document.getElementById(ElementId("chat-messages"))?.let { el ->
                el.scrollTop = el.scrollHeight.toDouble()
            }
        }

        fun send() {
            val content = input.trim()
            if (content.isEmpty() || sending) return
            val replyId = replyingTo?.id
            setSending(true)
            setInput("")
            setReplyingTo(null)
            launchApp {
                AppModule.nostrRepository.sendMessage(props.groupId, content, replyToMessageId = replyId)
                setSending(false)
            }
        }

        div {
            className = ClassName("chat-screen")

            div {
                className = ClassName("chat-header")
                button {
                    className = ClassName("secondary")
                    onClick = { navigate(Screen.Home) }
                    +"←"
                }
                span {
                    className = ClassName("chat-title")
                    +(props.groupName ?: props.groupId.take(12))
                }
                button {
                    className = ClassName("secondary chat-leave")
                    onClick = {
                        launchApp {
                            AppModule.nostrRepository.leaveGroup(props.groupId)
                            navigate(Screen.Home)
                        }
                    }
                    +"Leave"
                }
            }

            div {
                className = ClassName("chat-messages")
                id = ElementId("chat-messages")
                messages.forEach { message ->
                    val parent = replyParentId(message)?.let { messagesById[it] }
                    val messageReactions = reactions[message.id]
                    div {
                        key = message.id
                        className = ClassName("chat-msg")

                        if (parent != null) {
                            div {
                                className = ClassName("reply-quote")
                                +("↪ ${authorName(parent.pubkey)}: ${parent.content.take(80)}")
                            }
                        }

                        span {
                            className = ClassName("chat-author")
                            +authorName(message.pubkey)
                        }
                        div {
                            className = ClassName("chat-content")
                            renderMessageContent(message.content)
                        }

                        if (!messageReactions.isNullOrEmpty()) {
                            div {
                                className = ClassName("reactions")
                                messageReactions.forEach { (emoji, info) ->
                                    button {
                                        key = emoji
                                        className = ClassName("reaction-badge")
                                        onClick = {
                                            launchApp {
                                                AppModule.nostrRepository.sendReaction(props.groupId, message.id, message.pubkey, emoji)
                                            }
                                        }
                                        +"$emoji ${info.reactors.size}"
                                    }
                                }
                            }
                        }

                        div {
                            className = ClassName("msg-actions")
                            button {
                                className = ClassName("msg-action")
                                onClick = { setReplyingTo(message) }
                                +"Reply"
                            }
                            QUICK_EMOJIS.forEach { emoji ->
                                button {
                                    key = emoji
                                    className = ClassName("msg-action")
                                    onClick = {
                                        launchApp {
                                            AppModule.nostrRepository.sendReaction(props.groupId, message.id, message.pubkey, emoji)
                                        }
                                    }
                                    +emoji
                                }
                            }
                        }
                    }
                }
            }

            replyingTo?.let { target ->
                div {
                    className = ClassName("reply-banner")
                    span { +"Replying to ${authorName(target.pubkey)}" }
                    button {
                        onClick = { setReplyingTo(null) }
                        +"×"
                    }
                }
            }

            div {
                className = ClassName("chat-composer")
                textarea {
                    className = ClassName("chat-input")
                    value = input
                    placeholder = "Message…"
                    disabled = sending
                    rows = 1
                    onChange = { event -> setInput(event.currentTarget.value) }
                    onKeyDown = { event ->
                        if (event.key == "Enter" && !event.shiftKey) {
                            event.preventDefault()
                            send()
                        }
                    }
                }
                button {
                    disabled = sending || input.isBlank()
                    onClick = { send() }
                    +"Send"
                }
            }
        }
    }
