package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.Identifier
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.IdentifierRow
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import org.nostr.nostrord.web.navigation.pushHome
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.ClassName

external interface EditGroupModalProps : Props {
    var group: GroupMetadata
    var onClose: () -> Unit
}

/**
 * Edit-group modal — real port of the Compose EditGroupModal: name, description, image URL,
 * the Private / Closed access toggles, and the read-only group ID. Prefilled from the group;
 * Save calls `editGroup(groupId, name, about?, isPrivate, isClosed, picture?)`.
 */
val EditGroupModal =
    FC<EditGroupModalProps> { props ->
        val group = props.group
        val (name, setName) = useState { group.name ?: "" }
        val (about, setAbout) = useState { group.about ?: "" }
        val (picture, setPicture) = useState { group.picture ?: "" }
        val (isPrivate, setIsPrivate) = useState { !group.isPublic }
        val (isClosed, setIsClosed) = useState { !group.isOpen }
        val (isRestricted, setIsRestricted) = useState { group.isRestricted }
        val (isHidden, setIsHidden) = useState { group.isHidden }
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }
        val (confirmDelete, setConfirmDelete) = useState { false }
        val (deleting, setDeleting) = useState { false }

        fun delete() {
            setDeleting(true)
            setError(null)
            launchApp {
                when (val result = AppModule.nostrRepository.deleteGroup(group.id)) {
                    is Result.Success -> {
                        // The group is gone; leave its (now dead) page for Home.
                        props.onClose()
                        pushHome()
                    }
                    is Result.Error -> {
                        setDeleting(false)
                        setError(result.error.message.ifBlank { "Failed to delete group." })
                    }
                }
            }
        }

        fun submit() {
            if (name.isBlank()) {
                setError("Group name is required.")
                return
            }
            setError(null)
            setBusy(true)
            launchApp {
                val result =
                    AppModule.nostrRepository.editGroup(
                        groupId = group.id,
                        name = name.trim(),
                        about = about.trim().ifBlank { null },
                        isPrivate = isPrivate,
                        isClosed = isClosed,
                        isRestricted = isRestricted,
                        isHidden = isHidden,
                        picture = picture.trim().ifBlank { null },
                    )
                setBusy(false)
                when (result) {
                    is Result.Success -> props.onClose()
                    is Result.Error -> setError(result.error.message.ifBlank { "Failed to update group." })
                }
            }
        }

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
                            +"Edit Group"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

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
                    UploadButton {
                        cls = "upload-btn"
                        icon = Ic.Upload
                        imagesOnly = true
                        onUploaded = { setPicture(it.url) }
                        onError = { setError(it) }
                    }
                }

                div {
                    className = ClassName("access-section-title")
                    +"ACCESS SETTINGS"
                }
                accessToggle(Ic.Lock, "Private", "Only members can read group messages", isPrivate) { setIsPrivate(!isPrivate) }
                accessToggle(Ic.Block, "Closed", "Join requests are ignored (invite-only)", isClosed) { setIsClosed(!isClosed) }
                accessToggle(Ic.Send, "Restricted", "Only members can post messages", isRestricted) { setIsRestricted(!isRestricted) }
                accessToggle(Ic.VisibilityOff, "Hidden", "Hidden from non-members, not discoverable", isHidden) { setIsHidden(!isHidden) }

                div {
                    className = ClassName("access-section-title")
                    +"GROUP ID"
                }
                IdentifierRow { ids = listOf(Identifier("group id", group.id)) }

                div {
                    className = ClassName("access-section-title")
                    +"DANGER ZONE"
                }
                if (!confirmDelete) {
                    button {
                        className = ClassName("btn-danger")
                        onClick = { setConfirmDelete(true) }
                        +"Delete group"
                    }
                } else {
                    div {
                        className = ClassName("modal-subtitle")
                        +"This permanently deletes the group from the relay. This cannot be undone."
                    }
                    div {
                        className = ClassName("manage-actions")
                        button {
                            className = ClassName("btn-text")
                            disabled = deleting
                            onClick = { setConfirmDelete(false) }
                            +"Cancel"
                        }
                        button {
                            className = ClassName("btn-danger")
                            disabled = deleting
                            onClick = { delete() }
                            +(if (deleting) "Deleting…" else "Delete group")
                        }
                    }
                }

                if (error != null) {
                    div {
                        className = ClassName("modal-error")
                        +error
                    }
                }

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
                        +(if (busy) "Saving…" else "Save Changes")
                    }
                }
            }
        }
    }
