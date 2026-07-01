package org.nostr.nostrord.web.modals

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.groupIdentifiers
import org.nostr.nostrord.ui.screens.group.GroupAccessCopy
import org.nostr.nostrord.ui.screens.group.pendingJoinRequests
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.normalizeRelayUrl
import org.nostr.nostrord.utils.shortNpub
import org.nostr.nostrord.web.bridge.launchApp
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.GroupAvatarUploadRow
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.IdentifierRow
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.confirmDialog
import org.nostr.nostrord.web.components.copyToClipboard
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.searchInput
import org.nostr.nostrord.web.components.tabItem
import org.nostr.nostrord.web.components.useEscClose
import org.nostr.nostrord.web.navigation.pushHome
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.input
import react.dom.html.ReactHTML.option
import react.dom.html.ReactHTML.select
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
    Hierarchy("Hierarchy", Ic.AccountTree),
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

        // The Hierarchy tab only makes sense where the relay advertises NIP-29 subgroup support
        // (nip29:{subgroups:true} in its NIP-11); hide it elsewhere.
        val relayUrl = useStateFlow(AppModule.nostrRepository.currentRelayUrl)
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)
        val supportsSubgroups =
            (relayMetadata[relayUrl] ?: relayMetadata[relayUrl.normalizeRelayUrl()])?.supportsSubgroups == true
        val visibleTabs = ManageTab.entries.filter { it != ManageTab.Hierarchy || supportsSubgroups }

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
                        visibleTabs.forEach { t ->
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
                                    this.relayUrl = relayUrl
                                    onClose = props.onClose
                                }
                            ManageTab.Members -> ManageMembersSection { groupId = group.id }
                            ManageTab.Invites -> ManageInvitesSection { groupId = group.id }
                            ManageTab.Requests ->
                                ManageRequestsSection {
                                    groupId = group.id
                                    isOpen = group.isOpen
                                }
                            ManageTab.Hierarchy -> ManageHierarchySection { this.group = group }
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
    var relayUrl: String
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
        val relayMetadata = useStateFlow(AppModule.nostrRepository.relayMetadata)
        val relayHost = props.relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
        val relayIconUrl =
            (relayMetadata[props.relayUrl] ?: relayMetadata[props.relayUrl.normalizeRelayUrl()])?.icon

        GroupAvatarUploadRow {
            pictureUrl = picture
            seed = group.id
            this.name = name
            onPictureChange = { setPicture(it) }
            onError = { setError(it) }
        }
        div {
            className = ClassName("field-label")
            +"Name"
        }
        input {
            className = ClassName("modal-input")
            placeholder = "#example"
            value = name
            onChange = { e ->
                setName(e.currentTarget.value)
                setError(null)
            }
        }
        if (relayHost.isNotBlank()) {
            div {
                className = ClassName("group-side-banner-relay manage-relay-chip")
                WebAvatar {
                    url = relayIconUrl
                    seed = props.relayUrl
                    this.name = relayHost
                    kind = AvatarKind.RELAY
                    cls = "group-side-banner-relay-icon"
                }
                span {
                    className = ClassName("manage-relay-chip-host")
                    +relayHost
                }
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
            onChange = { e -> setAbout(e.currentTarget.value) }
        }

        div {
            className = ClassName("access-section-title")
            +"ACCESS"
        }
        accessToggle(Ic.Lock, GroupAccessCopy.PRIVATE_LABEL, GroupAccessCopy.PRIVATE_DESC, isPrivate) { setIsPrivate(!isPrivate) }
        accessToggle(Ic.Block, GroupAccessCopy.CLOSED_LABEL, GroupAccessCopy.CLOSED_DESC, isClosed) { setIsClosed(!isClosed) }
        accessToggle(Ic.Send, GroupAccessCopy.RESTRICTED_LABEL, GroupAccessCopy.RESTRICTED_DESC, isRestricted) { setIsRestricted(!isRestricted) }
        accessToggle(Ic.VisibilityOff, GroupAccessCopy.HIDDEN_LABEL, GroupAccessCopy.HIDDEN_DESC, isHidden) { setIsHidden(!isHidden) }

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
                className = ClassName("manage-danger-head")
                icon(Ic.Warning)
                span {
                    className = ClassName("manage-danger-title")
                    +"Delete group"
                }
            }
            div {
                className = ClassName("manage-danger-desc")
                +(
                    if (confirmDelete) {
                        "Are you sure? This permanently deletes the group from the relay and cannot be undone."
                    } else {
                        "This permanently deletes the group from the relay. This cannot be undone."
                    }
                    )
            }
            if (error != null) {
                div {
                    className = ClassName("modal-error")
                    +error
                }
            }
            div {
                className = ClassName("manage-danger-actions")
                if (!confirmDelete) {
                    button {
                        className = ClassName("btn-danger")
                        onClick = { setConfirmDelete(true) }
                        icon(Ic.Delete)
                        +"Delete group"
                    }
                } else {
                    button {
                        className = ClassName("btn-secondary")
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
                        icon(Ic.Delete)
                        +(if (deleting) "Deleting…" else "Confirm delete")
                    }
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
        // (pubkey, action) whose promote / demote / remove is awaiting confirmation.
        val (confirmAction, setConfirmAction) = useState<Pair<String, String>?> { null }
        // Pubkey whose row action menu (the chevron dropdown) is currently open.
        val (openMenu, setOpenMenu) = useState<String?> { null }
        val myPubkey = repo.getPublicKey()

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: shortNpub(pubkey)
        }

        val adminCount = members.count { it in admins }
        val memberCount = members.size - adminCount

        val filtered =
            members
                .filter {
                    when (tab) {
                        "Admins" -> it in admins
                        "Members" -> it !in admins
                        else -> true
                    }
                }
                .filter {
                    query.isBlank() ||
                        nameOf(it).contains(query, ignoreCase = true) ||
                        it.contains(query, ignoreCase = true) ||
                        Nip19.encodeNpub(it).contains(query, ignoreCase = true)
                }

        searchInput(
            placeholder = "Search members...",
            value = query,
            onChange = { setQuery(it) },
            compact = true,
        )
        // Per-category counts ride on the filter tabs (All / Admins / Members), styled like the
        // home tab strip (segmented pill) instead of plain chips.
        div {
            className = ClassName("tab-strip mod-tab-strip")
            tabItem(tab == "All", null, "All · ${members.size}") { setTab("All") }
            tabItem(tab == "Admins", null, "Admins · $adminCount") { setTab("Admins") }
            tabItem(tab == "Members", null, "Members · $memberCount") { setTab("Members") }
        }
        div {
            className = ClassName("mod-list member-list")
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
                    className = ClassName("mod-row member-card")
                    WebAvatar {
                        url = userMetadata[pubkey]?.picture
                        seed = pubkey
                        name = nameOf(pubkey)
                        cls = "mod-avatar"
                    }
                    div {
                        className = ClassName("mod-member-meta")
                        div {
                            className = ClassName("mod-member-line")
                            span {
                                className = ClassName("mod-name")
                                +nameOf(pubkey)
                            }
                            if (pubkey == myPubkey) {
                                span {
                                    className = ClassName("mod-you")
                                    +"YOU"
                                }
                            }
                        }
                        span {
                            className = ClassName("mod-npub")
                            +(Nip19.encodeNpub(pubkey).take(20) + "...")
                        }
                    }
                    if (isAdmin) {
                        span {
                            className = ClassName("member-admin")
                            +"ADMIN"
                        }
                    }
                    // No self-demote / self-remove: a NIP-29 relay only accepts moderation
                    // (kind 9000/9001) from an admin, so demoting yourself is one-way; you could
                    // not re-promote yourself and may be locked out. Use Leave group.
                    if (pubkey != myPubkey) {
                        div {
                            className = ClassName("mod-menu-wrap")
                            button {
                                className = ClassName("mod-menu-btn")
                                onClick = { setOpenMenu(if (openMenu == pubkey) null else pubkey) }
                                icon(Ic.ExpandMore)
                            }
                            if (openMenu == pubkey) {
                                // Full-screen click-catcher so a click anywhere else closes the menu.
                                div {
                                    className = ClassName("mod-menu-backdrop")
                                    onClick = { setOpenMenu(null) }
                                }
                                div {
                                    className = ClassName("mod-menu")
                                    button {
                                        className = ClassName("mod-menu-item")
                                        onClick = {
                                            setOpenMenu(null)
                                            setConfirmAction(pubkey to if (isAdmin) "demote" else "promote")
                                        }
                                        icon(Ic.Shield)
                                        span { +(if (isAdmin) "Remove Admin Role" else "Promote to Admin") }
                                    }
                                    button {
                                        className = ClassName("mod-menu-item danger")
                                        onClick = {
                                            setOpenMenu(null)
                                            setConfirmAction(pubkey to "remove")
                                        }
                                        icon(Ic.Close)
                                        span { +"Remove from Group" }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        confirmAction?.let { (pubkey, action) ->
            val title =
                when (action) {
                    "promote" -> "Promote to Admin"
                    "demote" -> "Remove Admin Role"
                    else -> "Remove from Group"
                }
            val desc =
                when (action) {
                    "promote" -> "${nameOf(pubkey)} will be able to manage members and group settings."
                    "demote" -> "${nameOf(pubkey)} will lose admin privileges."
                    else -> "${nameOf(pubkey)} will be removed from the group."
                }
            confirmDialog(
                title = title,
                body = desc,
                confirmLabel = "Confirm",
                danger = action == "remove",
                onCancel = { setConfirmAction(null) },
                onConfirm = {
                    setConfirmAction(null)
                    launchApp {
                        when (action) {
                            "promote" -> repo.addUser(props.groupId, pubkey, listOf("admin"))
                            "demote" -> repo.addUser(props.groupId, pubkey, emptyList())
                            else -> repo.removeUser(props.groupId, pubkey)
                        }
                    }
                },
            )
        }
    }

// ---- Hierarchy ----

private external interface ManageHierarchyProps : Props {
    var group: GroupMetadata
}

/**
 * Hierarchy tab: re-parent the group (make it a child of another group on the same relay) or
 * promote it back to root, plus a read-only list of its declared subgroups. Parent changes go
 * through one kind:9002 (updateGroupTopology); a parent must live on the same relay, and the
 * candidate list excludes the group itself and its own descendants so no cycle can form.
 */
private val ManageHierarchySection =
    FC<ManageHierarchyProps> { props ->
        val group = props.group
        val repo = AppModule.nostrRepository
        val relayUrl = useStateFlow(repo.currentRelayUrl)
        val groupsByRelay = useStateFlow(repo.groupsByRelay)
        val childrenByParent = useStateFlow(repo.childrenByParent)
        val groupAdmins = useStateFlow(repo.groupAdmins)
        val myPubkey = repo.getPublicKey()
        val (busy, setBusy) = useState { false }
        val (error, setError) = useState<String?> { null }

        val relayGroups = groupsByRelay[relayUrl].orEmpty()
        val parentId = group.parent
        val parentName =
            parentId?.let { pid -> relayGroups.firstOrNull { it.id == pid }?.name?.takeIf { it.isNotBlank() } ?: pid }

        // Transitive descendants of this group, excluded from parent candidates to prevent cycles.
        val descendants = HashSet<String>()
        val stack = ArrayDeque(childrenByParent[group.id].orEmpty())
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (descendants.add(id)) stack.addAll(childrenByParent[id].orEmpty())
        }
        // Only groups you administer are offered as a parent (you can't list every group on the
        // relay), and never this group, its current parent, or any of its descendants (cycles).
        val candidates =
            relayGroups
                .filter {
                    it.id != group.id &&
                        it.id != parentId &&
                        it.id !in descendants &&
                        myPubkey != null &&
                        myPubkey in groupAdmins[it.id].orEmpty()
                }
                .sortedBy { (it.name ?: it.id).lowercase() }

        // Re-parent through editGroup so the kind:9002 carries the group's FULL metadata + the
        // parent op (the relay can reject a bare parent-only 9002); fall back to a topology-only
        // event when we don't have that group's metadata cached (e.g. an id typed by hand).
        suspend fun reparent(target: GroupMetadata?, id: String, op: GroupManager.ParentOp): Result<Unit> = if (target != null) {
            repo.editGroup(
                groupId = target.id,
                name = target.name?.takeIf { it.isNotBlank() } ?: target.id,
                about = target.about,
                isPrivate = !target.isPublic,
                isClosed = !target.isOpen,
                isRestricted = target.isRestricted,
                isHidden = target.isHidden,
                picture = target.picture,
                parentOp = op,
            )
        } else {
            repo.updateGroupTopology(id, op)
        }

        fun applyParent(op: GroupManager.ParentOp) {
            setBusy(true)
            setError(null)
            launchApp {
                val r = reparent(group, group.id, op)
                setBusy(false)
                if (r is Result.Error) setError(r.error.message.ifBlank { "Failed to update hierarchy." })
            }
        }

        fun addSubgroup(childId: String) {
            if (childId.isBlank()) return
            val child = relayGroups.firstOrNull { it.id == childId }
            setBusy(true)
            setError(null)
            launchApp {
                val r = reparent(child, childId, GroupManager.ParentOp.SetTo(group.id))
                setBusy(false)
                if (r is Result.Error) setError(r.error.message.ifBlank { "Failed to add subgroup." })
            }
        }

        // Groups you administer that could become a subgroup here: not this group, not an
        // existing child, and not an ancestor/descendant (cycle). Only known groups, never the
        // relay's full list.
        val childCandidates =
            relayGroups
                .filter {
                    it.id != group.id &&
                        it.id != parentId &&
                        it.id != group.parent &&
                        it.parent != group.id &&
                        it.id !in descendants &&
                        myPubkey != null &&
                        myPubkey in groupAdmins[it.id].orEmpty()
                }
                .sortedBy { (it.name ?: it.id).lowercase() }

        div {
            className = ClassName("access-section-title")
            +"PARENT"
        }
        div {
            className = ClassName("hierarchy-current")
            +"Current: "
            span {
                className = ClassName("hierarchy-current-value")
                +(parentName ?: "Root group")
            }
        }
        div {
            className = ClassName("hierarchy-row")
            select {
                className = ClassName("modal-input")
                value = ""
                disabled = busy || candidates.isEmpty()
                onChange = { e ->
                    val id = e.currentTarget.value
                    if (id.isNotBlank()) applyParent(GroupManager.ParentOp.SetTo(id))
                }
                option {
                    value = ""
                    +(if (candidates.isEmpty()) "No other groups on this relay" else "Set parent...")
                }
                candidates.forEach { g ->
                    option {
                        key = g.id
                        value = g.id
                        +(g.name?.takeIf { it.isNotBlank() } ?: g.id)
                    }
                }
            }
            if (parentId != null) {
                button {
                    className = ClassName("btn-secondary")
                    disabled = busy
                    onClick = { applyParent(GroupManager.ParentOp.Detach) }
                    +"Make root"
                }
            }
        }
        if (error != null) {
            div {
                className = ClassName("modal-error")
                +error
            }
        }

        val subIds = childrenByParent[group.id].orEmpty()
        div {
            className = ClassName("access-section-title")
            +"SUBGROUPS (${subIds.size})"
        }
        div {
            className = ClassName("mod-list")
            if (subIds.isEmpty()) {
                div {
                    className = ClassName("mod-empty")
                    +"No subgroups."
                }
            }
            subIds.forEach { sid ->
                val sub = relayGroups.firstOrNull { it.id == sid }
                div {
                    key = sid
                    className = ClassName("mod-row")
                    span {
                        className = ClassName("mod-name")
                        +(sub?.name?.takeIf { it.isNotBlank() } ?: sid)
                    }
                }
            }
        }
        div {
            className = ClassName("hierarchy-row")
            select {
                className = ClassName("modal-input")
                value = ""
                disabled = busy || childCandidates.isEmpty()
                onChange = { e ->
                    val id = e.currentTarget.value
                    if (id.isNotBlank()) addSubgroup(id)
                }
                option {
                    value = ""
                    +(if (childCandidates.isEmpty()) "No groups you admin to add" else "Add a subgroup...")
                }
                childCandidates.forEach { g ->
                    option {
                        key = g.id
                        value = g.id
                        +(g.name?.takeIf { it.isNotBlank() } ?: g.id)
                    }
                }
            }
        }
        div {
            className = ClassName("modal-subtitle")
            +"Only groups you administer on this relay can be added as subgroups."
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
        val (error, setError) = useState<String?> { null }

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
                setError(null)
                launchApp {
                    val result = repo.createInviteCode(props.groupId)
                    setBusy(false)
                    if (result is Result.Error) {
                        // Surface the relay's reason; the common case is a relay that does not
                        // allow kind:9009 (invite codes).
                        val raw = (result.error.cause?.message ?: result.error.message)
                            .removePrefix("blocked: ")
                            .removePrefix("error: ")
                        setError(
                            if (raw.contains("9009") || raw.contains("not allowed", ignoreCase = true)) {
                                "This relay does not support invite codes."
                            } else {
                                raw.ifBlank { "Failed to create invite code." }
                            },
                        )
                    }
                }
            }
            +(if (busy) "Creating…" else "Create invite code")
        }
        if (error != null) {
            div {
                className = ClassName("modal-error")
                +error
            }
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
        // Hooks must run unconditionally and in a stable order, so they precede the
        // open-group early return (React errors with "rendered more hooks" otherwise).
        val repo = AppModule.nostrRepository
        val msgs = useStateFlow(repo.messages)[props.groupId].orEmpty()
        val members = useStateFlow(repo.groupMembers)[props.groupId].orEmpty().toSet()
        val userMetadata = useStateFlow(repo.userMetadata)

        val pending = pendingJoinRequests(msgs, members)

        fun nameOf(pubkey: String): String {
            val meta = userMetadata[pubkey]
            return meta?.displayName?.takeIf { it.isNotBlank() }
                ?: meta?.name?.takeIf { it.isNotBlank() }
                ?: shortNpub(pubkey)
        }

        if (pending.isEmpty()) {
            // Open groups auto-join, so an empty queue is the normal state.
            div {
                className = ClassName("mod-empty")
                +(
                    if (props.isOpen) {
                        "Open group: people join automatically, so there is no request queue."
                    } else {
                        "No pending requests"
                    }
                    )
            }
        } else {
            // Header with the count and a one-tap Accept all. Open groups still surface these
            // because some relays leave a kind:9021 pending on a leave + rejoin; the admin lets
            // them back in here (open-group policy is to accept everyone).
            div {
                className = ClassName("mod-requests-header")
                span {
                    className = ClassName("mod-requests-title")
                    +"${if (props.isOpen) "Rejoining" else "Requests"} (${pending.size})"
                }
                button {
                    className = ClassName("mod-btn primary")
                    onClick = { launchApp { pending.forEach { repo.addUser(props.groupId, it.pubkey) } } }
                    +"Accept all"
                }
            }
            if (props.isOpen) {
                div {
                    className = ClassName("mod-note")
                    +"These people left and asked to rejoin. Open groups accept everyone, so you can let them back in."
                }
            }
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
