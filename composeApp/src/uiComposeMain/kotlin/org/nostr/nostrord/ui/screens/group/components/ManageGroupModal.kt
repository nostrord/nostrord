package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.IdentifierRow
import org.nostr.nostrord.ui.components.avatars.UserGradientAvatar
import org.nostr.nostrord.ui.components.upload.UploadImageField
import org.nostr.nostrord.ui.groupIdentifiers
import org.nostr.nostrord.ui.screens.group.GroupViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.rememberClipboardWriter

/**
 * Tabs of the unified admin "Manage group" modal. Mirrors the web web/modals/ManageGroupModal
 * ManageTab: Info / Members / Invites / Requests / Hierarchy / Danger. Hierarchy is only shown
 * where the relay advertises NIP-29 subgroup support.
 */
enum class ManageTab(val label: String, val icon: ImageVector) {
    Info("Info", Icons.Default.Settings),
    Members("Members", Icons.Default.People),
    Invites("Invites", Icons.Default.Link),
    Requests("Requests", Icons.Default.Shield),
    Hierarchy("Hierarchy", Icons.Default.AccountTree),
    Danger("Danger", Icons.Default.Warning),
}

/**
 * Unified admin "Manage group" modal (native port of web web/modals/ManageGroupModal): a left tab
 * nav over the group's real moderation features (Info / Members / Invites / Requests / Hierarchy /
 * Danger). The card has a fixed height so switching tabs never resizes it; the content column
 * scrolls internally instead (parity with the web .manage-modal). All actions go through the
 * shared GroupViewModel so native and web can't drift.
 */
@Composable
fun ManageGroupModal(
    groupId: String,
    currentMetadata: GroupMetadata?,
    relayUrl: String,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit,
    initialTab: ManageTab = ManageTab.Info,
    supportsSubgroups: Boolean = true,
) {
    val vm = viewModel(key = groupId) { GroupViewModel(AppModule.nostrRepository, groupId) }
    var tab by remember { mutableStateOf(initialTab) }

    // Hierarchy only makes sense where the relay hosts subgroups; hide that tab otherwise.
    val visibleTabs = remember(supportsSubgroups) {
        ManageTab.entries.filter { it != ManageTab.Hierarchy || supportsSubgroups }
    }

    // Refresh metadata when the modal opens so the Info fields show current relay state.
    LaunchedEffect(groupId) { AppModule.nostrRepository.refreshGroupMetadata(groupId) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() }
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier =
                Modifier
                    .widthIn(max = 640.dp)
                    .fillMaxWidth(0.95f)
                    // Fixed height (not content-driven) so the card never resizes when the tab
                    // changes; min(90% of the window, 720dp) mirrors the web height:90vh/max:720px.
                    .fillMaxHeight(0.9f)
                    .heightIn(max = 720.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(Spacing.xxl)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Manage group",
                            style = NostrordTypography.ServerHeader,
                            color = NostrordColors.TextPrimary,
                            fontWeight = FontWeight.Bold,
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier =
                            Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .pointerHoverIcon(PointerIcon.Hand),
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    BoxWithConstraints(modifier = Modifier.fillMaxWidth().weight(1f)) {
                        // Narrow widths put the nav in a horizontal scroll row on top (web's
                        // <600px .manage-layout); wider widths use the left rail.
                        val wide = maxWidth >= 520.dp
                        if (wide) {
                            Row(modifier = Modifier.fillMaxSize()) {
                                ManageNav(
                                    tabs = visibleTabs,
                                    selected = tab,
                                    vertical = true,
                                    onSelect = { tab = it },
                                    modifier = Modifier.width(150.dp),
                                )
                                Spacer(modifier = Modifier.width(Spacing.lg))
                                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                    ManageTabContent(tab, vm, groupId, currentMetadata, relayUrl, onDeleted)
                                }
                            }
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                ManageNav(
                                    tabs = visibleTabs,
                                    selected = tab,
                                    vertical = false,
                                    onSelect = { tab = it },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Spacer(modifier = Modifier.height(Spacing.md))
                                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                    ManageTabContent(tab, vm, groupId, currentMetadata, relayUrl, onDeleted)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ManageTabContent(
    tab: ManageTab,
    vm: GroupViewModel,
    groupId: String,
    currentMetadata: GroupMetadata?,
    relayUrl: String,
    onDeleted: () -> Unit,
) {
    when (tab) {
        ManageTab.Info -> ManageInfoSection(groupId, currentMetadata)
        ManageTab.Members -> ManageMembersSection(vm, groupId)
        ManageTab.Invites -> ManageInvitesSection(vm, groupId, relayUrl)
        ManageTab.Requests -> ManageRequestsSection(vm, groupId, isOpen = currentMetadata?.isOpen != false)
        ManageTab.Hierarchy -> ManageHierarchySection(vm, groupId, currentMetadata, relayUrl)
        ManageTab.Danger -> ManageDangerSection(groupId, onDeleted)
    }
}

@Composable
private fun ManageNav(
    tabs: List<ManageTab>,
    selected: ManageTab,
    vertical: Boolean,
    onSelect: (ManageTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (vertical) {
        Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            tabs.forEach { ManageNavItem(it, it == selected) { onSelect(it) } }
        }
    } else {
        Row(
            modifier = modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
        ) {
            tabs.forEach { ManageNavItem(it, it == selected) { onSelect(it) } }
        }
    }
}

@Composable
private fun ManageNavItem(
    tab: ManageTab,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    Row(
        modifier =
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    selected -> NostrordColors.SurfaceVariant
                    hovered -> NostrordColors.HoverBackground
                    else -> Color.Transparent
                },
            )
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Icon(
            imageVector = tab.icon,
            contentDescription = null,
            tint = if (selected || hovered) NostrordColors.TextPrimary else NostrordColors.TextMuted,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = tab.label,
            style = NostrordTypography.Caption,
            color = if (selected || hovered) NostrordColors.TextPrimary else NostrordColors.TextMuted,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
        )
    }
}

// ---- Info ----

@Composable
private fun ManageInfoSection(
    groupId: String,
    currentMetadata: GroupMetadata?,
) {
    val scope = rememberCoroutineScope()
    var name by remember(currentMetadata) { mutableStateOf(currentMetadata?.name ?: "") }
    var about by remember(currentMetadata) { mutableStateOf(currentMetadata?.about ?: "") }
    var picture by remember(currentMetadata) { mutableStateOf(currentMetadata?.picture ?: "") }
    var isPrivate by remember(currentMetadata) { mutableStateOf(currentMetadata?.isPublic == false) }
    var isClosed by remember(currentMetadata) { mutableStateOf(currentMetadata?.isOpen == false) }
    var isRestricted by remember(currentMetadata) { mutableStateOf(currentMetadata?.isRestricted == true) }
    var isHidden by remember(currentMetadata) { mutableStateOf(currentMetadata?.isHidden == true) }
    var isSaving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        EditFieldLabel("Name")
        Spacer(modifier = Modifier.height(Spacing.xs))
        OutlinedTextField(
            value = name,
            onValueChange = {
                name = it
                error = null
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = editFieldColors(),
            shape = RoundedCornerShape(8.dp),
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        EditFieldLabel("Description")
        Spacer(modifier = Modifier.height(Spacing.xs))
        OutlinedTextField(
            value = about,
            onValueChange = { about = it },
            minLines = 3,
            maxLines = 5,
            modifier = Modifier.fillMaxWidth(),
            colors = editFieldColors(),
            shape = RoundedCornerShape(8.dp),
        )

        Spacer(modifier = Modifier.height(Spacing.lg))

        UploadImageField(
            label = "Group Image URL",
            value = picture,
            onValueChange = { picture = it },
            placeholder = "https://example.com/image.jpg",
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(Spacing.xxl))

        Text("ACCESS", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        Spacer(modifier = Modifier.height(Spacing.sm))
        EditAccessToggleRow(Icons.Default.Lock, "Private", "Only members can read messages.", isPrivate) { isPrivate = it }
        Spacer(modifier = Modifier.height(Spacing.xs))
        EditAccessToggleRow(Icons.AutoMirrored.Filled.Send, "Restricted (announcements)", "Only admins can post; members can only read.", isRestricted) { isRestricted = it }
        Spacer(modifier = Modifier.height(Spacing.xs))
        EditAccessToggleRow(Icons.Default.VisibilityOff, "Hidden", "The relay hides the group from non-members, not discoverable.", isHidden) { isHidden = it }
        Spacer(modifier = Modifier.height(Spacing.xs))
        EditAccessToggleRow(Icons.Default.Block, "Closed", "Joining needs approval or an invite.", isClosed) { isClosed = it }

        if (error != null) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(error!!, style = NostrordTypography.Caption, color = NostrordColors.Error)
        }

        Spacer(modifier = Modifier.height(Spacing.xxl))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        error = "Group name is required."
                        return@Button
                    }
                    isSaving = true
                    scope.launch {
                        val result =
                            AppModule.nostrRepository.editGroup(
                                groupId = groupId,
                                name = name.trim(),
                                about = about.trim().ifBlank { null },
                                isPrivate = isPrivate,
                                isClosed = isClosed,
                                isRestricted = isRestricted,
                                isHidden = isHidden,
                                picture = picture.trim().ifBlank { null },
                            )
                        isSaving = false
                        if (result is Result.Error) {
                            error = result.error.cause?.message ?: result.error.message ?: "Failed to update group."
                        }
                    }
                },
                enabled = !isSaving && name.isNotBlank(),
                colors = primaryButtonColors(),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                if (isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                }
                Text(if (isSaving) "Saving…" else "Save Changes", style = NostrordTypography.Button)
            }
        }
    }
}

// ---- Members ----

@Composable
private fun ManageMembersSection(
    vm: GroupViewModel,
    groupId: String,
) {
    val members by vm.groupMembers.collectAsState()
    val admins by vm.groupAdmins.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()
    val myPubkey = remember { vm.getPublicKey() }
    var query by remember { mutableStateOf("") }
    var filter by remember { mutableStateOf("All") }
    var confirmRemove by remember { mutableStateOf<String?>(null) }

    val adminSet = admins[groupId].orEmpty().toSet()
    fun nameOf(pubkey: String): String {
        val meta = userMetadata[pubkey]
        return meta?.displayName?.takeIf { it.isNotBlank() }
            ?: meta?.name?.takeIf { it.isNotBlank() }
            ?: (pubkey.take(8) + "…")
    }

    val filtered =
        members[groupId].orEmpty()
            .filter {
                when (filter) {
                    "Admins" -> it in adminSet
                    "Members" -> it !in adminSet
                    else -> true
                }
            }
            .filter { query.isBlank() || nameOf(it).contains(query, ignoreCase = true) }

    Column(modifier = Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search members...", color = NostrordColors.TextMuted, style = NostrordTypography.Caption) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = editFieldColors(),
            shape = RoundedCornerShape(8.dp),
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            listOf("All", "Admins", "Members").forEach { label ->
                ModTab(label, label == filter) { filter = label }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.md))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (filtered.isEmpty()) {
                item { ModEmpty("No members found") }
            }
            items(filtered, key = { it }) { pubkey ->
                val isAdmin = pubkey in adminSet
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(NostrordColors.SurfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MemberAvatar(pubkey)
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            nameOf(pubkey),
                            color = NostrordColors.TextPrimary,
                            style = NostrordTypography.Caption,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (isAdmin) {
                            Text("ADMIN", color = NostrordColors.Primary, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                    if (pubkey == myPubkey) {
                        Text("You", color = NostrordColors.TextMuted, style = MaterialTheme.typography.labelSmall)
                    } else if (confirmRemove == pubkey) {
                        TextButton(onClick = {
                            confirmRemove = null
                            vm.removeUser(pubkey)
                        }) {
                            Text("Confirm", color = NostrordColors.Error, style = NostrordTypography.Caption)
                        }
                        TextButton(onClick = { confirmRemove = null }) {
                            Text("Cancel", color = NostrordColors.TextSecondary, style = NostrordTypography.Caption)
                        }
                    } else {
                        TextButton(onClick = { if (isAdmin) vm.demoteFromAdmin(pubkey) else vm.promoteToAdmin(pubkey) }) {
                            Text(if (isAdmin) "Demote" else "Promote", color = NostrordColors.TextSecondary, style = NostrordTypography.Caption)
                        }
                        TextButton(onClick = { confirmRemove = pubkey }) {
                            Text("Remove", color = NostrordColors.Error, style = NostrordTypography.Caption)
                        }
                    }
                }
            }
        }
    }
}

// ---- Invites ----

@Composable
private fun ManageInvitesSection(
    vm: GroupViewModel,
    groupId: String,
    relayUrl: String,
) {
    val copyToClipboard = rememberClipboardWriter()
    val messages by vm.messages.collectAsState()
    val relayMetadata by vm.relayMetadata.collectAsState()
    var busy by remember { mutableStateOf(false) }

    val msgs = messages[groupId].orEmpty()
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

    Column(modifier = Modifier.fillMaxSize()) {
        Button(
            onClick = {
                busy = true
                vm.createInviteCode { busy = false }
            },
            enabled = !busy,
            colors = primaryButtonColors(),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth().pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(if (busy) "Creating…" else "Create invite code", style = NostrordTypography.Button)
        }
        Spacer(modifier = Modifier.height(Spacing.lg))
        Text("ACTIVE CODES (${codes.size})", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        Spacer(modifier = Modifier.height(Spacing.sm))
        LazyColumn(modifier = Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (codes.isEmpty()) {
                item { ModEmpty("No active invite codes") }
            }
            items(codes, key = { it.second }) { (code, eventId, _) ->
                Column(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
                        .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            code,
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { copyToClipboard(code) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.ContentCopy, "Copy code", tint = NostrordColors.TextSecondary, modifier = Modifier.size(16.dp))
                        }
                        IconButton(onClick = { vm.revokeInviteCode(eventId) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Delete, "Revoke", tint = NostrordColors.Error, modifier = Modifier.size(16.dp))
                        }
                    }
                    IdentifierRow(ids = groupIdentifiers(relayUrl, groupId, relayPubkey, code))
                }
            }
        }
    }
}

// ---- Requests ----

@Composable
private fun ManageRequestsSection(
    vm: GroupViewModel,
    groupId: String,
    isOpen: Boolean,
) {
    val messages by vm.messages.collectAsState()
    val members by vm.groupMembers.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()

    if (isOpen) {
        ModEmpty("Open group: people join automatically, so there is no request queue.")
        return
    }

    val msgs = messages[groupId].orEmpty()
    val memberSet = members[groupId].orEmpty().toSet()
    val lastLeave =
        msgs.filter { it.kind == 9022 }
            .groupBy { it.pubkey }
            .mapValues { (_, events) -> events.maxOf { it.createdAt } }
    val pending =
        msgs.filter { it.kind == 9021 && it.pubkey !in memberSet }
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
        ModEmpty("No pending requests")
        return
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        items(pending, key = { it.id }) { req ->
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(NostrordColors.SurfaceVariant.copy(alpha = 0.5f))
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MemberAvatar(req.pubkey)
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    nameOf(req.pubkey),
                    color = NostrordColors.TextPrimary,
                    style = NostrordTypography.Caption,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { vm.approveJoinRequest(req.pubkey) }) {
                    Text("Approve", color = NostrordColors.Primary, style = NostrordTypography.Caption)
                }
                TextButton(onClick = { vm.rejectJoinRequest(req.id) }) {
                    Text("Reject", color = NostrordColors.Error, style = NostrordTypography.Caption)
                }
            }
        }
    }
}

// ---- Hierarchy ----

@Composable
private fun ManageHierarchySection(
    vm: GroupViewModel,
    groupId: String,
    currentMetadata: GroupMetadata?,
    relayUrl: String,
) {
    val scope = rememberCoroutineScope()
    val groupsByRelay by vm.groupsByRelay.collectAsState()
    val childrenByParent by vm.childrenByParent.collectAsState()
    val groupAdmins by vm.groupAdmins.collectAsState()
    val myPubkey = remember { vm.getPublicKey() }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val relayGroups = groupsByRelay[relayUrl].orEmpty()
    val parentId = currentMetadata?.parent
    val parentName = parentId?.let { pid -> relayGroups.firstOrNull { it.id == pid }?.name?.takeIf { it.isNotBlank() } ?: pid }

    // Transitive descendants, excluded from parent candidates to prevent cycles.
    val descendants = remember(childrenByParent, groupId) {
        val acc = HashSet<String>()
        val stack = ArrayDeque(childrenByParent[groupId].orEmpty())
        while (stack.isNotEmpty()) {
            val id = stack.removeLast()
            if (acc.add(id)) stack.addAll(childrenByParent[id].orEmpty())
        }
        acc
    }
    val parentCandidates =
        relayGroups
            .filter { it.id != groupId && it.id != parentId && it.id !in descendants && myPubkey != null && myPubkey in groupAdmins[it.id].orEmpty() }
            .sortedBy { (it.name ?: it.id).lowercase() }
    val childCandidates =
        relayGroups
            .filter { it.id != groupId && it.id != parentId && it.parent != groupId && it.id !in descendants && myPubkey != null && myPubkey in groupAdmins[it.id].orEmpty() }
            .sortedBy { (it.name ?: it.id).lowercase() }
    val subIds = childrenByParent[groupId].orEmpty()

    // Reparent through editGroup so the kind:9002 carries the group's full metadata + the parent
    // op (the relay can reject a bare parent-only 9002); fall back to a topology-only event when
    // the target's metadata is not cached. Mirrors the web reparent helper.
    suspend fun reparent(target: GroupMetadata?, id: String, op: GroupManager.ParentOp): Result<Unit> = if (target != null) {
        AppModule.nostrRepository.editGroup(
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
        AppModule.nostrRepository.updateGroupTopology(id, op)
    }

    fun apply(target: GroupMetadata?, id: String, op: GroupManager.ParentOp, fail: String) {
        busy = true
        error = null
        scope.launch {
            val r = reparent(target, id, op)
            busy = false
            if (r is Result.Error) error = r.error.message?.ifBlank { fail } ?: fail
        }
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Text("PARENT", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            "Current: ${parentName ?: "Root group"}",
            style = NostrordTypography.Caption,
            color = NostrordColors.TextSecondary,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            GroupPickerDropdown(
                placeholder = if (parentCandidates.isEmpty()) "No other groups on this relay" else "Set parent...",
                candidates = parentCandidates,
                enabled = !busy && parentCandidates.isNotEmpty(),
                modifier = Modifier.weight(1f),
                onPick = { g -> apply(currentMetadata, groupId, GroupManager.ParentOp.SetTo(g.id), "Failed to update hierarchy.") },
            )
            if (parentId != null) {
                OutlinedButton(
                    onClick = { apply(currentMetadata, groupId, GroupManager.ParentOp.Detach, "Failed to update hierarchy.") },
                    enabled = !busy,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Text("Make root", style = NostrordTypography.Button, color = NostrordColors.TextSecondary)
                }
            }
        }
        if (error != null) {
            Spacer(modifier = Modifier.height(Spacing.md))
            Text(error!!, style = NostrordTypography.Caption, color = NostrordColors.Error)
        }

        Spacer(modifier = Modifier.height(Spacing.xxl))
        Text("SUBGROUPS (${subIds.size})", style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        Spacer(modifier = Modifier.height(Spacing.sm))
        if (subIds.isEmpty()) {
            ModEmpty("No subgroups.")
        } else {
            subIds.forEach { sid ->
                val sub = relayGroups.firstOrNull { it.id == sid }
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.xs),
                ) {
                    Text(
                        sub?.name?.takeIf { it.isNotBlank() } ?: sid,
                        style = NostrordTypography.Caption,
                        color = NostrordColors.TextPrimary,
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.sm))
        GroupPickerDropdown(
            placeholder = if (childCandidates.isEmpty()) "No groups you admin to add" else "Add a subgroup...",
            candidates = childCandidates,
            enabled = !busy && childCandidates.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
            onPick = { g -> apply(g, g.id, GroupManager.ParentOp.SetTo(groupId), "Failed to add subgroup.") },
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            "Only groups you administer on this relay can be added as subgroups.",
            style = MaterialTheme.typography.labelSmall,
            color = NostrordColors.TextMuted,
        )
    }
}

// ---- Danger ----

@Composable
private fun ManageDangerSection(
    groupId: String,
    onDeleted: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .border(1.dp, NostrordColors.Error.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .background(NostrordColors.Error.copy(alpha = 0.06f), RoundedCornerShape(12.dp))
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Icon(Icons.Default.Warning, null, tint = NostrordColors.Error, modifier = Modifier.size(18.dp))
            Text("Delete group", style = NostrordTypography.MessageBody, color = NostrordColors.Error, fontWeight = FontWeight.Bold)
        }
        Text(
            if (confirmDelete) {
                "Are you sure? This permanently deletes the group from the relay and cannot be undone."
            } else {
                "This permanently deletes the group from the relay. This cannot be undone."
            },
            style = NostrordTypography.Caption,
            color = NostrordColors.TextSecondary,
        )
        if (error != null) {
            Text(error!!, style = NostrordTypography.Caption, color = NostrordColors.Error)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End)) {
            if (!confirmDelete) {
                Button(
                    onClick = { confirmDelete = true },
                    colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    Icon(Icons.Default.Delete, null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text("Delete group", style = NostrordTypography.Button)
                }
            } else {
                TextButton(onClick = { confirmDelete = false }, enabled = !deleting) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
                Button(
                    onClick = {
                        deleting = true
                        error = null
                        scope.launch {
                            when (val result = AppModule.nostrRepository.deleteGroup(groupId)) {
                                is Result.Success -> onDeleted()
                                is Result.Error -> {
                                    deleting = false
                                    error = result.error.message.ifBlank { "Failed to delete group." }
                                }
                            }
                        }
                    },
                    enabled = !deleting,
                    colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error, contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                ) {
                    if (deleting) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                        Spacer(modifier = Modifier.width(Spacing.sm))
                    }
                    Text(if (deleting) "Deleting…" else "Confirm delete", style = NostrordTypography.Button)
                }
            }
        }
    }
}

// ---- Shared bits ----

@Composable
private fun ModTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand),
        shape = RoundedCornerShape(6.dp),
        color = if (selected) NostrordColors.Primary else NostrordColors.SurfaceVariant,
    ) {
        Text(
            label,
            style = NostrordTypography.Caption,
            color = if (selected) Color.White else NostrordColors.TextMuted,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ModEmpty(text: String) {
    Box(modifier = Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
        Text(text, color = NostrordColors.TextMuted, style = NostrordTypography.Caption)
    }
}

@Composable
private fun MemberAvatar(pubkey: String) {
    Box(modifier = Modifier.size(32.dp).clip(CircleShape), contentAlignment = Alignment.Center) {
        UserGradientAvatar(seed = pubkey, size = 32.dp)
    }
}

/** Picker that renders like an input and drops down the candidate groups (web's <select>). */
@Composable
private fun GroupPickerDropdown(
    placeholder: String,
    candidates: List<GroupMetadata>,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onPick: (GroupMetadata) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(NostrordColors.InputBackground)
                .clickable(enabled = enabled) { expanded = true }
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                placeholder,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Icon(Icons.Default.KeyboardArrowDown, null, tint = NostrordColors.TextMuted, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = NostrordColors.Surface,
        ) {
            candidates.forEach { g ->
                DropdownMenuItem(
                    text = { Text(g.name?.takeIf { it.isNotBlank() } ?: g.id, color = NostrordColors.TextPrimary, style = NostrordTypography.Caption) },
                    onClick = {
                        expanded = false
                        onPick(g)
                    },
                )
            }
        }
    }
}

@Composable
private fun primaryButtonColors() = ButtonDefaults.buttonColors(
    containerColor = NostrordColors.Primary,
    contentColor = Color.White,
    disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.5f),
    disabledContentColor = Color.White.copy(alpha = 0.5f),
)
