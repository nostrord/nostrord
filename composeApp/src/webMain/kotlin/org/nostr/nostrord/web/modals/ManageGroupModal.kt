package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.groupIdentifiers
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.IdentifierRow
import org.nostr.nostrord.web.components.UploadButton
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.useEscClose
import org.nostr.nostrord.web.navigation.pushHome
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.span
import react.dom.html.ReactHTML.textarea
import react.useState
import web.cssom.ClassName

external interface ManageGroupModalProps : Props {
    var group: GroupMetadata
    var onClose: () -> Unit

    /** Tab to open on first render (case-insensitive name, e.g. "members"). Defaults to Info. */
    var initialTab: String?
}

private enum class ManageTab(val label: String, val ic: Ic) {
    Info("Info", Ic.Settings),
    Members("Members", Ic.People),
    Invites("Invites", Ic.Link),
    Requests("Requests", Ic.Shield),
    Danger("Danger", Ic.Warning),
}

/**
 * Unified admin "Manage group" modal (port of the prototype GroupManage): a left tab
 * nav (Info / Members / Invites / Requests / Danger) over the group's real moderation
 * features. Tabs adapt to the group type (Requests is auto-only for open groups), and the
 * destructive delete lives on its own Danger tab so it stays out of the edit-and-save flow.
 */
val ManageGroupModal =
    FC<ManageGroupModalProps> { props ->
        val group = props.group
        val (tab, setTab) =
            useState {
                ManageTab.entries.firstOrNull { it.name.equals(props.initialTab, ignoreCase = true) } ?: ManageTab.Info
            }
        useEscClose { props.onClose() }

        div {
            className = ClassName("modal-overlay")
            onClick = { props.onClose() }
            div {
                className = ClassName("modal-card manage-modal")
                onClick = { it.stopPropagation() }

                div {
                    className = ClassName("modal-header")
                    div {
                        className = ClassName("modal-header-text")
                        div {
                            className = ClassName("modal-title")
                            +"Manage group"
                        }
                    }
                    button {
                        className = ClassName("modal-close")
                        onClick = { props.onClose() }
                        icon(Ic.Close)
                    }
                }

                div {
                    className = ClassName("manage-layout")
                    div {
                        className = ClassName("manage-nav")
                        ManageTab.entries.forEach { t ->
                            button {
                                key = t.name
                                className = ClassName(if (t == tab) "manage-nav-item selected" else "manage-nav-item")
                                onClick = { setTab(t) }
                                icon(t.ic)
                                span { +t.label }
                            }
                        }
                    }
                    div {
                        className = ClassName("manage-content")
                        when (tab) {
                            ManageTab.Info ->
                                ManageInfoSection {
                                    this.group = group
                                    onClose = props.onClose
                                }
                            ManageTab.Members -> ManageMembersSection { groupId = group.id }
                            ManageTab.Invites -> ManageInvitesSection { groupId = group.id }
                            ManageTab.Requests ->
                                ManageRequestsSection {
                                    groupId = group.id
                                    isOpen = group.isOpen
                                }
                            ManageTab.Danger ->
                                ManageDangerSection {
                                    this.group = group
                                    onClose = props.onClose
                                }
                        }
                    }
                }
            }
        }
    }

// ---- Info ----

private external interface ManageInfoProps : Props {
    var group: GroupMetadata
    var onClose: () -> Unit
}

private val ManageInfoSection =
    FC<ManageInfoProps> { props ->
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

        div {
            className = ClassName("manage-avatar-row")
            WebAvatar {
                url = picture.ifBlank { null }
                seed = group.id
                this.name = name.ifBlank { group.id }
                kind = AvatarKind.GROUP
                cls = "manage-avatar"
            }
            UploadButton {
                cls = "btn-secondary"
                icon = Ic.Upload
                label = "Change photo"
                imagesOnly = true
                onUploaded = { setPicture(it.url) }
                onError = { setError(it) }
            }
        }
        div {
            className = ClassName("field-label")
            +"Name"
        }
        input {
            className = ClassName("modal-input")
            value = name
            onChange = { e ->
                setName(e.currentTarget.value)
                setError(null)
            }
        }
        div {
            className = ClassName("field-label")
            +"Description"
        }
        textarea {
            className = ClassName("modal-textarea")
            rows = 3
            value = about
            onChange = { e -> setAbout(e.currentTarget.value) }
        }

        div {
            className = ClassName("access-section-title")
            +"ACCESS"
        }
        accessToggle(Ic.Lock, "Private", "Only members can read messages.", isPrivate) { setIsPrivate(!isPrivate) }
        accessToggle(Ic.Send, "Restricted (announcements)", "Only admins can post; members can only read.", isRestricted) { setIsRestricted(!isRestricted) }
        accessToggle(Ic.VisibilityOff, "Hidden", "The relay hides the group from non-members, not discoverable.", isHidden) { setIsHidden(!isHidden) }
        accessToggle(Ic.Block, "Closed", "Joining needs approval or an invite.", isClosed) { setIsClosed(!isClosed) }

        if (error != null) {
            div {
                className = ClassName("modal-error")
                +error
            }
        }

        div {
            className = ClassName("modal-footer")
            button {
                className = ClassName("btn-primary")
                disabled = name.isBlank() || busy
                onClick = {
                    if (name.isBlank()) {
                        setError("Group name is required.")
                    } else {
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
                }
                +(if (busy) "Saving…" else "Save Changes")
            }
        }
    }

// ---- Danger ----

private val ManageDangerSection =
    FC<ManageInfoProps> { props ->
        val group = props.group
        val (confirmDelete, setConfirmDelete) = useState { false }
        val (deleting, setDeleting) = useState { false }
        val (error, setError) = useState<String?> { null }

        div {
            className = ClassName("manage-danger")
            div {
                className = ClassName("manage-danger-title")
                +"Delete group"
            }
            div {
                className = ClassName("modal-subtitle")
                +"This permanently deletes the group from the relay. This cannot be undone."
            }
            if (!confirmDelete) {
                button {
                    className = ClassName("btn-danger")
                    onClick = { setConfirmDelete(true) }
                    icon(Ic.Delete)
                    +"Delete group"
                }
            } else {
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
                        onClick = {
                            setDeleting(true)
                            setError(null)
                            launchApp {
                                when (val result = AppModule.nostrRepository.deleteGroup(group.id)) {
                                    is Result.Success -> {
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
        }
    }

// ---- Members ----

private external interface ManageGroupIdProps : Props {
    var groupId: String
}

private val ManageMembersSection =
    FC<ManageGroupIdProps> { props ->
        val repo = AppModule.nostrRepository
        val members = useStateFlow(repo.groupMembers)[props.groupId].orEmpty()
        val admins = useStateFlow(repo.groupAdmins)[props.groupId].orEmpty().toSet()
        val userMetadata = useStateFlow(repo.userMetadata)
        val (tab, setTab) = useState { "All" }
        val (query, setQuery) = useState { "" }

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (pubkey.take(8) + "…")
        }

        val filtered =
            members
                .filter {
                    when (tab) {
                        "Admins" -> it in admins
                        "Members" -> it !in admins
                        else -> true
                    }
                }
                .filter { query.isBlank() || nameOf(it).contains(query, ignoreCase = true) }

        input {
            className = ClassName("modal-input")
            placeholder = "Search members..."
            value = query
            onChange = { e -> setQuery(e.currentTarget.value) }
        }
        div {
            className = ClassName("mod-tabs")
            listOf("All", "Admins", "Members").forEach { label ->
                button {
                    key = label
                    className = ClassName(if (label == tab) "mod-tab selected" else "mod-tab")
                    onClick = { setTab(label) }
                    +label
                }
            }
        }
        div {
            className = ClassName("mod-list")
            if (filtered.isEmpty()) {
                div {
                    className = ClassName("mod-empty")
                    +"No members found"
                }
            }
            filtered.forEach { pubkey ->
                val isAdmin = pubkey in admins
                div {
                    key = pubkey
                    className = ClassName("mod-row")
                    WebAvatar {
                        url = userMetadata[pubkey]?.picture
                        seed = pubkey
                        name = nameOf(pubkey)
                        cls = "mod-avatar"
                    }
                    div {
                        className = ClassName("mod-name-wrap")
                        span {
                            className = ClassName("mod-name")
                            +nameOf(pubkey)
                        }
                        if (isAdmin) {
                            span {
                                className = ClassName("member-admin")
                                +"ADMIN"
                            }
                        }
                    }
                    div {
                        className = ClassName("mod-actions")
                        button {
                            className = ClassName("mod-btn")
                            onClick = {
                                launchApp {
                                    repo.addUser(props.groupId, pubkey, if (isAdmin) emptyList() else listOf("admin"))
                                }
                            }
                            +(if (isAdmin) "Demote" else "Promote")
                        }
                        button {
                            className = ClassName("mod-btn danger")
                            onClick = { launchApp { repo.removeUser(props.groupId, pubkey) } }
                            +"Remove"
                        }
                    }
                }
            }
        }
    }

// ---- Invites ----

private val ManageInvitesSection =
    FC<ManageGroupIdProps> { props ->
        val repo = AppModule.nostrRepository
        val msgs = useStateFlow(repo.messages)[props.groupId].orEmpty()
        val relayUrl = useStateFlow(repo.currentRelayUrl)
        val relayMetadata = useStateFlow(repo.relayMetadata)
        val (busy, setBusy) = useState { false }

        val revoked =
            msgs.filter { it.kind == 9005 }
                .flatMap { m -> m.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) } }
                .toSet()
        val codes =
            msgs.filter { it.kind == 9009 && it.id !in revoked }
                .mapNotNull { m ->
                    val code = m.tags.firstOrNull { it.firstOrNull() == "code" }?.getOrNull(1) ?: return@mapNotNull null
                    Triple(code, m.id, m.createdAt)
                }
                .sortedByDescending { it.third }
        val relayPubkey = relayMetadata[relayUrl]?.pubkey ?: relayMetadata[relayUrl.trimEnd('/')]?.pubkey

        button {
            className = ClassName("btn-primary block")
            disabled = busy
            onClick = {
                setBusy(true)
                launchApp {
                    repo.createInviteCode(props.groupId)
                    setBusy(false)
                }
            }
            +(if (busy) "Creating…" else "Create invite code")
        }
        div {
            className = ClassName("access-section-title")
            +"ACTIVE CODES (${codes.size})"
        }
        div {
            className = ClassName("mod-list")
            if (codes.isEmpty()) {
                div {
                    className = ClassName("mod-empty")
                    +"No active invite codes"
                }
            }
            codes.forEach { (code, eventId, _) ->
                div {
                    key = eventId
                    className = ClassName("mod-row invite-code-row")
                    div {
                        className = ClassName("invite-code-head")
                        span {
                            className = ClassName("mod-code")
                            +code
                        }
                        div {
                            className = ClassName("mod-actions")
                            button {
                                className = ClassName("mod-btn")
                                onClick = { copyToClipboard(code) }
                                +"Copy code"
                            }
                            button {
                                className = ClassName("mod-btn danger")
                                onClick = { launchApp { repo.revokeInviteCode(props.groupId, eventId) } }
                                +"Revoke"
                            }
                        }
                    }
                    IdentifierRow { ids = groupIdentifiers(relayUrl, props.groupId, relayPubkey, code) }
                }
            }
        }
    }

// ---- Requests ----

private external interface ManageRequestsProps : Props {
    var groupId: String
    var isOpen: Boolean
}

private val ManageRequestsSection =
    FC<ManageRequestsProps> { props ->
        if (props.isOpen) {
            div {
                className = ClassName("mod-empty")
                +"Open group: people join automatically, so there is no request queue."
            }
            return@FC
        }
        val repo = AppModule.nostrRepository
        val msgs = useStateFlow(repo.messages)[props.groupId].orEmpty()
        val members = useStateFlow(repo.groupMembers)[props.groupId].orEmpty().toSet()
        val userMetadata = useStateFlow(repo.userMetadata)

        val lastLeave =
            msgs.filter { it.kind == 9022 }
                .groupBy { it.pubkey }
                .mapValues { (_, events) -> events.maxOf { it.createdAt } }
        val pending =
            msgs.filter { it.kind == 9021 && it.pubkey !in members }
                .filter { req -> lastLeave[req.pubkey].let { it == null || req.createdAt > it } }
                .distinctBy { it.pubkey }
                .sortedByDescending { it.createdAt }

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: (pubkey.take(8) + "…")
        }

        if (pending.isEmpty()) {
            div {
                className = ClassName("mod-empty")
                +"No pending requests"
            }
        } else {
            div {
                className = ClassName("mod-list")
                pending.forEach { req ->
                    div {
                        key = req.id
                        className = ClassName("mod-row")
                        WebAvatar {
                            url = userMetadata[req.pubkey]?.picture
                            seed = req.pubkey
                            name = nameOf(req.pubkey)
                            cls = "mod-avatar"
                        }
                        span {
                            className = ClassName("mod-name")
                            +nameOf(req.pubkey)
                        }
                        div {
                            className = ClassName("mod-actions")
                            button {
                                className = ClassName("mod-btn primary")
                                onClick = { launchApp { repo.addUser(props.groupId, req.pubkey) } }
                                +"Approve"
                            }
                            button {
                                className = ClassName("mod-btn danger")
                                onClick = { launchApp { repo.rejectJoinRequest(props.groupId, req.id) } }
                                +"Reject"
                            }
                        }
                    }
                }
            }
        }
    }
