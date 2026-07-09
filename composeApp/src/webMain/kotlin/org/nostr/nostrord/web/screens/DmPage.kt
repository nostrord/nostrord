package org.nostr.nostrord.web.screens

import kotlinx.browser.document
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.dm.DmChatItem
import org.nostr.nostrord.ui.screens.dm.DmViewModel
import org.nostr.nostrord.ui.screens.dm.buildDmChatItems
import org.nostr.nostrord.ui.screens.profile.ProfilePageViewModel
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.formatDateTime
import org.nostr.nostrord.utils.formatTime
import org.nostr.nostrord.web.DmConversationList
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.EmojiPicker
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.uploadBlob
import org.nostr.nostrord.web.components.useEscClose
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLDivElement
import web.html.HTMLTextAreaElement

external interface DmPageProps : Props {
    /** Peer of the open conversation; null shows the section's empty hero. */
    var pubkey: String?
    var onOpenProfile: (UserRoute) -> Unit
    var onOpenConversation: (DmRoute) -> Unit
    var onOpenDrawer: () -> Unit
}

/**
 * Direct-message conversation page (prototype DirectMessage, NIP-17 style). The
 * message backend does not exist yet: the conversation intro and the composer are
 * in place, with sending disabled until NIP-17 lands. Mirrors the Compose
 * ui/screens/dm/DmPageScreen.
 */
val DmPage =
    FC<DmPageProps> { props ->
        val pubkey = props.pubkey
        if (pubkey == null) {
            div {
                className = ClassName("dm-page")
                div {
                    className = ClassName("page-header")
                    button {
                        className = ClassName("icon-btn frame-menu-btn")
                        onClick = { props.onOpenDrawer() }
                        icon(Ic.Menu)
                    }
                    icon(Ic.Mail)
                    span {
                        className = ClassName("page-header-title")
                        +"Direct messages"
                    }
                }
                // Desktop: the conversation list lives in the sidebar, so the main area is an
                // empty hero. Mobile has no visible sidebar, so it shows the list here instead
                // (the two are toggled by CSS).
                div {
                    className = ClassName("dm-hero")
                    div {
                        className = ClassName("dm-hero-tile")
                        +"✉️"
                    }
                    h2 { +"Your direct messages" }
                    p { +"Pick a conversation on the side or start a new one with someone you follow." }
                }
                div {
                    className = ClassName("dm-page-convos")
                    DmConversationList {
                        activePubkey = null
                        onOpenConversation = { props.onOpenConversation(it) }
                        // The drawer hosts the DM sidebar's search, where a new conversation starts.
                        onStartConversation = { props.onOpenDrawer() }
                    }
                }
            }
            return@FC
        }

        val vm = useViewModel("dm-$pubkey") { ProfilePageViewModel(AppModule.nostrRepository, pubkey) }
        val metadata = useStateFlow(vm.metadata)
        val dmVm = useViewModel { DmViewModel(AppModule.nostrRepository) }
        val messages = useStateFlow(dmVm.messagesByPeer)[pubkey].orEmpty()
        // Mark the conversation read while it is open (and as new messages stream in).
        useEffect(pubkey, messages.size) {
            if (messages.isNotEmpty()) dmVm.markRead(pubkey)
        }
        // Open a conversation pinned to the latest message (scroll to the bottom), like a chat.
        val messagesRef = useRef<HTMLDivElement>(null)
        useEffect(pubkey, messages.size) {
            messagesRef.current?.let { el -> el.scrollTop = el.scrollHeight.toDouble() }
        }
        val (text, setText) = useState { "" }
        val (sending, setSending) = useState { false }
        val send = {
            if (text.isNotBlank() && !sending) {
                setSending(true)
                dmVm.send(
                    pubkey,
                    text,
                    onSuccess = {
                        setText("")
                        setSending(false)
                    },
                    onFailure = { setSending(false) },
                )
            }
        }
        val name =
            metadata?.displayName?.takeIf { it.isNotBlank() }
                ?: metadata?.name?.takeIf { it.isNotBlank() }
                ?: vm.npub.take(12) + "..."

        // Composer media + emoji, mirroring the group ChatComposer (no mentions / formatting here).
        val (emojiOpen, setEmojiOpen) = useState { false }
        val (uploadCount, setUploadCount) = useState { 0 }
        val (uploadError, setUploadError) = useState<String?> { null }
        val composerInputRef = useRef<HTMLTextAreaElement>(null)
        useEscClose { if (emojiOpen) setEmojiOpen(false) }

        fun isMediaMime(type: String?): Boolean = type != null && (type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/"))

        // Upload a pasted / dropped file and append its URL to the draft (parity with the group composer).
        fun handleMediaFile(file: dynamic) {
            setUploadCount { it + 1 }
            launchApp {
                try {
                    when (val r = uploadBlob(file)) {
                        is Result.Success -> setText { prev -> if (prev.isBlank()) r.data.url else "$prev ${r.data.url}" }
                        is Result.Error -> setUploadError(r.error.message)
                    }
                } finally {
                    setUploadCount { it - 1 }
                }
            }
        }

        fun insertAtCursor(s: String) {
            val ta = composerInputRef.current
            if (ta == null) {
                setText { it + s }
                return
            }
            ta.focus()
            document.asDynamic().execCommand("insertText", false, s)
        }

        div {
            className = ClassName("dm-page")
            div {
                className = ClassName("page-header")
                button {
                    className = ClassName("icon-btn frame-menu-btn")
                    onClick = { props.onOpenDrawer() }
                    icon(Ic.Menu)
                }
                button {
                    className = ClassName("dm-peer")
                    onClick = { props.onOpenProfile(UserRoute(pubkey)) }
                    WebAvatar {
                        url = metadata?.picture
                        seed = pubkey
                        this.name = name
                        cls = "dm-peer-avatar"
                    }
                    span {
                        className = ClassName("page-header-title")
                        +name
                    }
                }
                span {
                    className = ClassName("dm-chip")
                    +"DM · encrypted"
                }
            }

            div {
                className = ClassName("dm-messages")
                ref = messagesRef
                div {
                    className = ClassName("dm-intro")
                    WebAvatar {
                        url = metadata?.picture
                        seed = pubkey
                        this.name = name
                        cls = "dm-intro-avatar link"
                        onClick = { props.onOpenProfile(UserRoute(pubkey)) }
                    }
                    div {
                        className = ClassName("dm-intro-name link")
                        onClick = { props.onOpenProfile(UserRoute(pubkey)) }
                        +name
                    }
                    div {
                        className = ClassName("dm-intro-text")
                        +"Beginning of your direct conversation with $name. Direct messages are encrypted (NIP-17)."
                    }
                }
                buildDmChatItems(messages).forEach { item ->
                    when (item) {
                        is DmChatItem.DateSeparator ->
                            div {
                                key = "sep-${item.label}"
                                className = ClassName("date-sep")
                                span {
                                    className = ClassName("date-sep-label")
                                    +item.label
                                }
                            }
                        is DmChatItem.Message -> {
                            val m = item.message
                            div {
                                key = m.id
                                className =
                                    ClassName(
                                        buildString {
                                            append("dm-msg")
                                            if (m.mine) append(" mine")
                                            if (!item.firstInGroup) append(" grouped")
                                        },
                                    )
                                // WhatsApp/Telegram-style: the clock floats bottom-right inside
                                // every bubble (rides the last text line when it fits, wraps below
                                // otherwise); hover shows the full date.
                                div {
                                    className = ClassName("dm-bubble")
                                    title = formatDateTime(m.createdAt)
                                    +m.content
                                    span {
                                        className = ClassName("dm-bubble-time")
                                        +formatTime(m.createdAt)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            div {
                className = ClassName("dm-composer-wrap")
                div {
                    className = ClassName("dm-composer")
                    UploadButton {
                        cls = "dm-composer-btn"
                        icon = Ic.AttachFile
                        busy = uploadCount > 0
                        onBusyChange = { b -> setUploadCount { if (b) it + 1 else it - 1 } }
                        onPickerClosed = { composerInputRef.current?.focus() }
                        onUploaded = { upload -> setText { prev -> if (prev.isBlank()) upload.url else "$prev ${upload.url}" } }
                        onError = { setUploadError(it) }
                    }
                    textarea {
                        ref = composerInputRef
                        rows = 1
                        value = text
                        placeholder = "Message $name"
                        onChange = { setText((it.target as HTMLTextAreaElement).value) }
                        onKeyDown = { e ->
                            if (e.key == "Enter" && !e.shiftKey) {
                                e.preventDefault()
                                send()
                            }
                        }
                        onPaste = { event ->
                            val items = event.asDynamic().clipboardData?.items
                            val count = (items?.length as? Int) ?: 0
                            for (i in 0 until count) {
                                val item = items[i]
                                val type = item.type.unsafeCast<String?>()
                                if (item.kind == "file" && isMediaMime(type)) {
                                    val file = item.getAsFile()
                                    if (file != null) {
                                        event.preventDefault()
                                        handleMediaFile(file)
                                    }
                                }
                            }
                        }
                        onDragOver = { it.preventDefault() }
                        onDrop = { event ->
                            val files = event.asDynamic().dataTransfer?.files
                            val count = (files?.length as? Int) ?: 0
                            if (count > 0) event.preventDefault()
                            for (i in 0 until count) {
                                val file = files[i]
                                if (isMediaMime(file.type.unsafeCast<String?>())) handleMediaFile(file)
                            }
                        }
                    }
                    button {
                        className = ClassName(if (emojiOpen) "dm-composer-btn active" else "dm-composer-btn")
                        title = "Emoji"
                        onClick = { setEmojiOpen(!emojiOpen) }
                        icon(Ic.EmojiEmotions)
                    }
                    button {
                        className = ClassName("dm-composer-btn send")
                        title = "Send"
                        disabled = (text.isBlank() && uploadCount == 0) || uploadCount > 0 || sending
                        onMouseDown = { e -> e.preventDefault() }
                        onClick = { send() }
                        if (sending) span { className = ClassName("btn-spinner") } else icon(Ic.Send)
                    }
                    if (emojiOpen) {
                        div {
                            className = ClassName("emoji-overlay")
                            onClick = { setEmojiOpen(false) }
                            EmojiPicker {
                                onPick = { emoji ->
                                    insertAtCursor(emoji)
                                    setEmojiOpen(false)
                                }
                            }
                        }
                    }
                }
            }
            uploadError?.let { uploadErrorDialog(it) { setUploadError(null) } }
        }
    }

/** Minimal "upload failed" dialog, parity with the group composer's. */
private fun ChildrenBuilder.uploadErrorDialog(message: String, onDismiss: () -> Unit) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onDismiss() }
        div {
            className = ClassName("modal-card")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-header")
                div {
                    className = ClassName("modal-title")
                    +"Upload failed"
                }
            }
            div {
                className = ClassName("modal-subtitle")
                +message
            }
            div {
                className = ClassName("modal-actions")
                button {
                    className = ClassName("btn-primary")
                    onClick = { onDismiss() }
                    +"OK"
                }
            }
        }
    }
}
