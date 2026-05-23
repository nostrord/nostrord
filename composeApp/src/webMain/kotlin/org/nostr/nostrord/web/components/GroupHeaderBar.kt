package org.nostr.nostrord.web.components

import kotlinx.browser.window
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.navigation.navigate
import org.nostr.nostrord.web.upload.UploadButton
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.code
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.label
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.checkbox

external interface GroupHeaderBarProps : Props {
    var groupId: String
    var groupName: String?
}

private enum class GroupModal { None, Info, Edit, AddMember, Invite }

private fun ChildrenBuilder.modalBox(title: String, onClose: () -> Unit, body: ChildrenBuilder.() -> Unit) {
    div {
        className = ClassName("modal-overlay")
        onClick = { onClose() }
        div {
            className = ClassName("modal-box")
            onClick = { it.stopPropagation() }
            div {
                className = ClassName("modal-header")
                span { +title }
                button {
                    className = ClassName("modal-close")
                    onClick = { onClose() }
                    +"×"
                }
            }
            body()
        }
    }
}

/**
 * Group chat header — title (opens info), admin gear menu (edit / add member / invite /
 * delete) and Leave. Owns the group-management modals. Mirrors the Compose GroupHeader +
 * GroupInfo/Edit/AddMember/InviteCodes modals.
 */
val GroupHeaderBar =
    FC<GroupHeaderBarProps> { props ->
        val groupsByRelay = useStateFlow(AppModule.nostrRepository.groupsByRelay)
        val groupAdmins = useStateFlow(AppModule.nostrRepository.groupAdmins)
        val activeId = useStateFlow(AppModule.accountStore.activeId)

        val group = groupsByRelay.values.flatten().firstOrNull { it.id == props.groupId }
        val isAdmin = activeId != null && activeId in (groupAdmins[props.groupId] ?: emptyList())
        val displayName = group?.name ?: props.groupName ?: props.groupId.take(12)

        val (modal, setModal) = useState { GroupModal.None }
        val (menuOpen, setMenuOpen) = useState { false }
        val (eName, setEName) = useState { "" }
        val (eAbout, setEAbout) = useState { "" }
        val (ePicture, setEPicture) = useState { "" }
        val (ePrivate, setEPrivate) = useState { false }
        val (eClosed, setEClosed) = useState { false }
        val (memberInput, setMemberInput) = useState { "" }
        val (inviteCode, setInviteCode) = useState<String?> { null }

        fun openEdit() {
            setEName(group?.name ?: "")
            setEAbout(group?.about ?: "")
            setEPicture(group?.picture ?: "")
            setEPrivate(group?.isPublic == false)
            setEClosed(group?.isOpen == false)
            setMenuOpen(false)
            setModal(GroupModal.Edit)
        }

        fun saveEdit() {
            if (eName.isBlank()) return
            launchApp {
                AppModule.nostrRepository.editGroup(props.groupId, eName.trim(), eAbout.trim().ifBlank { null }, ePrivate, eClosed, ePicture.trim().ifBlank { null })
            }
            setModal(GroupModal.None)
        }

        fun addMember() {
            val entered = memberInput.trim()
            val hex = if (entered.startsWith("npub1")) (Nip19.decode(entered) as? Nip19.Entity.Npub)?.pubkey else entered
            if (hex == null || hex.length != 64) return
            launchApp { AppModule.nostrRepository.addUser(props.groupId, hex) }
            setMemberInput("")
            setModal(GroupModal.None)
        }

        fun createInvite() {
            setMenuOpen(false)
            launchApp {
                val result = AppModule.nostrRepository.createInviteCode(props.groupId)
                if (result is Result.Success) setInviteCode(result.data)
            }
        }

        fun deleteGroup() {
            setMenuOpen(false)
            if (window.confirm("Delete this group? This cannot be undone.")) {
                launchApp {
                    AppModule.nostrRepository.deleteGroup(props.groupId)
                    navigate(Screen.Home)
                }
            }
        }

        div {
            className = ClassName("chat-header")
            div {
                className = ClassName("chat-title clickable")
                onClick = { setModal(GroupModal.Info) }
                val picture = group?.picture
                if (!picture.isNullOrBlank()) {
                    img {
                        className = ClassName("chat-header-avatar")
                        src = picture
                        alt = ""
                    }
                }
                span { +displayName }
            }
            if (isAdmin) {
                button {
                    className = ClassName("secondary")
                    onClick = { setMenuOpen(!menuOpen) }
                    +"⚙"
                }
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

        if (menuOpen) {
            modalBox("Manage group", { setMenuOpen(false) }) {
                button {
                    className = ClassName("account-menu-action")
                    onClick = { openEdit() }
                    +"Edit group"
                }
                button {
                    className = ClassName("account-menu-action")
                    onClick = {
                        setMenuOpen(false)
                        setModal(GroupModal.AddMember)
                    }
                    +"Add member"
                }
                button {
                    className = ClassName("account-menu-action")
                    onClick = { createInvite() }
                    +"Create invite code"
                }
                button {
                    className = ClassName("account-menu-action danger")
                    onClick = { deleteGroup() }
                    +"Delete group"
                }
            }
        }

        when (modal) {
            GroupModal.Info ->
                modalBox(displayName, { setModal(GroupModal.None) }) {
                    group?.about?.takeIf { it.isNotBlank() }?.let {
                        p { +it }
                    }
                    p {
                        className = ClassName("muted")
                        +((if (group?.isPublic == false) "Private" else "Public") + " · " + (if (group?.isOpen == false) "Closed" else "Open"))
                    }
                    code {
                        className = ClassName("key-box")
                        +props.groupId
                    }
                }
            GroupModal.Edit ->
                modalBox("Edit group", { setModal(GroupModal.None) }) {
                    input {
                        className = ClassName("modal-input")
                        placeholder = "Group name"
                        value = eName
                        onChange = { event -> setEName(event.currentTarget.value) }
                    }
                    input {
                        className = ClassName("modal-input")
                        placeholder = "About (optional)"
                        value = eAbout
                        onChange = { event -> setEAbout(event.currentTarget.value) }
                    }
                    input {
                        className = ClassName("modal-input")
                        placeholder = "Picture URL"
                        value = ePicture
                        onChange = { event -> setEPicture(event.currentTarget.value) }
                    }
                    UploadButton {
                        label = "Upload picture"
                        accept = "image/*"
                        onUploaded = { setEPicture(it) }
                    }
                    label {
                        className = ClassName("toggle-row")
                        input {
                            type = InputType.checkbox
                            checked = ePrivate
                            onChange = { event -> setEPrivate(event.currentTarget.checked) }
                        }
                        span { +"Private" }
                    }
                    label {
                        className = ClassName("toggle-row")
                        input {
                            type = InputType.checkbox
                            checked = eClosed
                            onChange = { event -> setEClosed(event.currentTarget.checked) }
                        }
                        span { +"Closed" }
                    }
                    button {
                        className = ClassName("modal-primary")
                        disabled = eName.isBlank()
                        onClick = { saveEdit() }
                        +"Save"
                    }
                }
            GroupModal.AddMember ->
                modalBox("Add member", { setModal(GroupModal.None) }) {
                    p {
                        className = ClassName("muted")
                        +"Paste an npub or 64-char hex pubkey."
                    }
                    input {
                        className = ClassName("modal-input")
                        placeholder = "npub1… or hex"
                        value = memberInput
                        onChange = { event -> setMemberInput(event.currentTarget.value) }
                    }
                    button {
                        className = ClassName("modal-primary")
                        disabled = memberInput.isBlank()
                        onClick = { addMember() }
                        +"Add"
                    }
                }
            else -> Unit
        }

        inviteCode?.let { codeValue ->
            modalBox("Invite code", { setInviteCode(null) }) {
                p {
                    className = ClassName("muted")
                    +"Share this code so others can join the group."
                }
                code {
                    className = ClassName("key-box")
                    +codeValue
                }
                button {
                    className = ClassName("modal-primary")
                    onClick = {
                        val clip = window.navigator.asDynamic().clipboard
                        if (clip != null) clip.writeText(codeValue)
                    }
                    +"Copy"
                }
            }
        }
    }
