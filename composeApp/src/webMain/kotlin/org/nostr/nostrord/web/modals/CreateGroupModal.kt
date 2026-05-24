package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import react.FC
import react.Props
import react.dom.html.ReactHTML.a
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.select
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.ClassName

external interface CreateGroupModalProps : Props {
    var onClose: () -> Unit

    /** When true, render as "Create Subgroup" (a child of the current group). */
    var subgroup: Boolean?

    /** Parent group id when creating a subgroup. */
    var parentGroupId: String?

    /** Relay to create on; defaults to the active relay. */
    var relayUrl: String?
}

/**
 * Create-group modal — layout-first React port of the Compose [CreateGroupModal]: name,
 * optional group ID, relay selector, description, image URL, and the Private / Closed
 * access toggles. Submit is stubbed (closes); wiring to the repository comes after the
 * layout is validated.
 */
val CreateGroupModal =
    FC<CreateGroupModalProps> { props ->
        val isSubgroup = props.subgroup == true
        val repo = AppModule.nostrRepository
        val kind10009 = useStateFlow(repo.kind10009Relays)
        val groupTagRelays = useStateFlow(repo.groupTagRelays)
        val currentRelayUrl = useStateFlow(repo.currentRelayUrl)
        val relayList =
            (kind10009.toList() + groupTagRelays.toList() + currentRelayUrl)
                .filter { it.isNotBlank() }
                .distinct()

        val (name, setName) = useState { "" }
        val (groupId, setGroupId) = useState { "" }
        val (selectedRelay, setSelectedRelay) = useState { props.relayUrl ?: currentRelayUrl }
        val (about, setAbout) = useState { "" }
        val (picture, setPicture) = useState { "" }
        val (isPrivate, setIsPrivate) = useState { false }
        val (isClosed, setIsClosed) = useState { false }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        val relayWebUrl = selectedRelay.replace("wss://", "https://").replace("ws://", "http://")

        fun submit() {
            if (name.isBlank()) {
                setError("Group name is required.")
                return
            }
            setError(null)
            setBusy(true)
            launchApp {
                val result =
                    if (isSubgroup && props.parentGroupId != null) {
                        repo.createSubgroup(
                            parentGroupId = props.parentGroupId!!,
                            name = name.trim(),
                            about = about.trim().ifBlank { null },
                            relayUrl = selectedRelay,
                            isPrivate = isPrivate,
                            isClosed = isClosed,
                            picture = picture.trim().ifBlank { null },
                            customGroupId = groupId.trim().ifBlank { null },
                        )
                    } else {
                        repo.createGroup(
                            name = name.trim(),
                            about = about.trim().ifBlank { null },
                            relayUrl = selectedRelay,
                            isPrivate = isPrivate,
                            isClosed = isClosed,
                            picture = picture.trim().ifBlank { null },
                            customGroupId = groupId.trim().ifBlank { null },
                        )
                    }
                setBusy(false)
                when (result) {
                    is Result.Success -> props.onClose()
                    is Result.Error -> setError(result.error.message.ifBlank { "Failed to create group." })
                }
            }
        }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card")
                onClick = { it.stopPropagation() }

                // Header
                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +(if (isSubgroup) "Create Subgroup" else "Create a Group")
                        }
                        div {
                            className = ClassName("modal-subtitle")
                            +"Give your new group a name and description. You can always change these later."
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        +"✕"
                    }
                }

                // Group Name
                div {
                    className = ClassName("field-label")
                    +"Group Name"
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "#example"
                    value = name
                    onChange = { event ->
                        setName(event.currentTarget.value)
                        setError(null)
                    }
                }

                // Group ID (optional)
                div {
                    className = ClassName("field-label")
                    +"Group ID (optional)"
                }
                input {
                    className = ClassName("modal-input")
                    placeholder = "my-group"
                    value = groupId
                    onChange = { event ->
                        val cleaned =
                            event.currentTarget.value.lowercase().filter { c ->
                                c.isLetterOrDigit() || c == '-' || c == '_'
                            }
                        setGroupId(cleaned)
                    }
                }
                div {
                    className = ClassName("field-hint")
                    +"Leave empty for a random ID. The relay may override your choice."
                }

                // Relay
                div {
                    className = ClassName("field-label")
                    +"Relay"
                }
                select {
                    className = ClassName("modal-select")
                    value = selectedRelay
                    onChange = { event -> setSelectedRelay(event.currentTarget.value) }
                    relayList.forEach { relay ->
                        option {
                            value = relay
                            +relay.removePrefix("wss://")
                        }
                    }
                }
                div {
                    className = ClassName("field-hint")
                    span { +"Some relays require creating groups via their website. " }
                    a {
                        className = ClassName("inline-link")
                        href = relayWebUrl
                        +"Open →"
                    }
                }

                // Description
                div {
                    className = ClassName("field-label")
                    +"Description"
                }
                textarea {
                    className = ClassName("modal-textarea")
                    placeholder = "What is this group about?"
                    rows = 3
                    value = about
                    onChange = { event -> setAbout(event.currentTarget.value) }
                }

                // Image URL
                div {
                    className = ClassName("field-label")
                    +"Group Image URL"
                }
                div {
                    className = ClassName("upload-field")
                    input {
                        className = ClassName("modal-input flush")
                        placeholder = "https://example.com/image.jpg"
                        value = picture
                        onChange = { event -> setPicture(event.currentTarget.value) }
                    }
                    button {
                        className = ClassName("upload-btn")
                        +"⤴"
                    }
                }

                // Access settings
                div {
                    className = ClassName("access-section-title")
                    +"ACCESS SETTINGS"
                }
                accessToggle(
                    icon = "🔒",
                    label = "Private",
                    description = "Only members can read group messages",
                    checked = isPrivate,
                    onToggle = { setIsPrivate(!isPrivate) },
                )
                accessToggle(
                    icon = "🚫",
                    label = "Closed",
                    description = "Join requests are ignored (invite-only)",
                    checked = isClosed,
                    onToggle = { setIsClosed(!isClosed) },
                )

                if (error != null) {
                    div {
                        className = ClassName("modal-error")
                        +error
                    }
                }

                // Footer
                div {
                    className = ClassName("modal-footer")
                    button {
                        className = ClassName("btn-text")
                        onClick = { props.onClose() }
                        +"Cancel"
                    }
                    button {
                        className = ClassName("btn-primary")
                        disabled = name.isBlank() || busy
                        onClick = { submit() }
                        +(if (busy) "Creating…" else if (isSubgroup) "Create Subgroup" else "Create Group")
                    }
                }
            }
        }
    }
