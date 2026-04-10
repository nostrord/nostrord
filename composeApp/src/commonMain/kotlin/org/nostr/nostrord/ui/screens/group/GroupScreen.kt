package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.upload.UploadResult
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.ui.screens.group.components.EditGroupModal
import org.nostr.nostrord.ui.screens.group.components.GroupInfoModal
import org.nostr.nostrord.ui.screens.group.components.InviteCode
import org.nostr.nostrord.ui.screens.group.components.InviteCodesModal
import org.nostr.nostrord.ui.screens.group.components.JoinRequestsModal
import org.nostr.nostrord.ui.screens.group.components.UserProfileModal
import org.nostr.nostrord.ui.screens.group.model.buildChatItems
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun GroupScreen(
    groupId: String,
    groupName: String?,
    onNavigateHome: () -> Unit = {},
    onNavigateToGroup: (groupId: String, groupName: String?) -> Unit = { _, _ -> },
    showServerRail: Boolean = true, // When false, server rail is handled by parent shell
    onOpenDrawer: () -> Unit = {},
    forceDesktop: Boolean = false,
    pendingInviteCode: String? = null,
    onInviteCodeConsumed: () -> Unit = {}
) {
    val vm = viewModel(key = groupId) { GroupViewModel(AppModule.nostrRepository, groupId) }

    var selectedChannel by remember { mutableStateOf("general") }

    val allMessages by vm.messages.collectAsState()

    // derivedStateOf: only recomposes downstream when the filtered result actually changes,
    // not when unrelated state (reactions, metadata) triggers a recomposition pass.
    val messages by remember(groupId) {
        derivedStateOf {
            val allGroupMessages = allMessages[groupId] ?: emptyList()
            if (selectedChannel == "general") {
                allGroupMessages.filter { message ->
                    !message.tags.any { it.size >= 2 && it[0] == "channel" }
                }
            } else {
                allGroupMessages.filter { message ->
                    message.tags.any { it.size >= 2 && it[0] == "channel" && it[1] == selectedChannel }
                }
            }
        }
    }

    val isSending by vm.isSending.collectAsState()
    val sendError by vm.sendError.collectAsState()
    val deleteMessageError by vm.deleteMessageError.collectAsState()
    val reactionError by vm.reactionError.collectAsState()
    val moderationError by vm.moderationError.collectAsState()
    val connectionState by vm.connectionState.collectAsState()
    val joinedGroups by vm.joinedGroups.collectAsState()
    val groups by vm.groups.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()
    val allReactions by vm.reactions.collectAsState()
    val allGroupMembers by vm.groupMembers.collectAsState()
    val allGroupAdmins by vm.groupAdmins.collectAsState()
    val loadingMembersSet by vm.loadingMembers.collectAsState()
    val currentRelayUrl by vm.currentRelayUrl.collectAsState()
    val currentUserPubkey = vm.getPublicKey()

    // Get current group metadata
    val currentGroupMetadata = remember(groups, groupId) {
        groups.find { it.id == groupId }
    }

    // Admin detection: check if current user is in kind:39001 admins list
    val isAdmin = remember(allGroupAdmins, groupId, currentUserPubkey) {
        currentUserPubkey != null && currentUserPubkey in (allGroupAdmins[groupId] ?: emptyList())
    }

    // Pagination state
    val isLoadingMoreMap by vm.isLoadingMore.collectAsState()
    val hasMoreMessagesMap by vm.hasMoreMessages.collectAsState()
    val isLoadingMore = isLoadingMoreMap[groupId] ?: false
    val hasMoreMessages = hasMoreMessagesMap[groupId] ?: true

    var messageInput by remember { mutableStateOf("") }
    var mentions by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // displayName -> pubkey
    var replyingToMessage by remember { mutableStateOf<NostrGroupClient.NostrMessage?>(null) }
    var pendingUploads by remember { mutableStateOf<List<UploadResult>>(emptyList()) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showGroupInfoModal by remember { mutableStateOf(false) }
    var showEditGroupModal by remember { mutableStateOf(false) }
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    var messageToDelete by remember { mutableStateOf<NostrGroupClient.NostrMessage?>(null) }
    var selectedUserPubkey by remember { mutableStateOf<String?>(null) }
    var showMemberSheet by remember { mutableStateOf(false) }
    var memberToRemove by remember { mutableStateOf<MemberInfo?>(null) }
    var showJoinRequestsModal by remember { mutableStateOf(false) }
    var showInviteCodesModal by remember { mutableStateOf(false) }
    var createdInviteCode by remember { mutableStateOf<String?>(null) }
    var resolvedRequestPubkeys by remember(groupId) { mutableStateOf(emptySet<String>()) }
    val isJoined = joinedGroups.contains(groupId)

    // Invite code from deep link — kept for UI pre-fill even after auto-join fires.
    val initialInviteCode = remember { pendingInviteCode }

    // Track whether we've already attempted auto-join to prevent duplicates.
    var autoJoinFired by remember { mutableStateOf(false) }

    // Auto-join with invite code from deep link / URL navigation
    LaunchedEffect(pendingInviteCode) {
        val code = pendingInviteCode ?: return@LaunchedEffect
        onInviteCodeConsumed()
        if (!autoJoinFired) {
            autoJoinFired = true
            vm.joinGroup(code)
        }
    }

    // Member pubkey source: prefer kind:39002 (authoritative), fall back to
    // message-derived pubkeys. Wrapped in derivedStateOf so the downstream
    // groupMembers derivation observes changes to both sources reactively.
    val memberPubkeys by remember(groupId) {
        derivedStateOf {
            val k39002 = allGroupMembers[groupId] ?: emptyList()
            if (k39002.isNotEmpty()) k39002
            else (allMessages[groupId] ?: emptyList()).map { it.pubkey }.distinct()
        }
    }

    // Resolves display names/pictures when metadata arrives. Only emits a new
    // list when the resolved content actually differs (structural equality).
    val adminPubkeys by remember(groupId) {
        derivedStateOf { allGroupAdmins[groupId] ?: emptyList() }
    }

    val groupMembers by remember(groupId) {
        derivedStateOf {
            memberPubkeys.map { pubkey ->
                val metadata = userMetadata[pubkey]
                MemberInfo(
                    pubkey = pubkey,
                    displayName = metadata?.displayName
                        ?: metadata?.name
                        ?: pubkey.take(8) + "...",
                    picture = metadata?.picture,
                    isAdmin = pubkey in adminPubkeys
                )
            }.sortedWith(compareByDescending<MemberInfo> { it.isAdmin }.thenBy { it.displayName.lowercase() })
        }
    }

    // Pending join requests: kind 9021 messages whose pubkey is NOT yet a member,
    // not already resolved in this session, and has no newer leave request (9022).
    val pendingJoinRequests by remember(groupId) {
        derivedStateOf {
            val msgs = allMessages[groupId] ?: emptyList()
            val members = (allGroupMembers[groupId] ?: emptyList()).toSet()
            // Latest leave timestamp per pubkey
            val lastLeave: Map<String, Long> = msgs
                .filter { it.kind == 9022 }
                .groupBy { it.pubkey }
                .mapValues { (_, events) -> events.maxOf { it.createdAt } }
            msgs
                .filter { it.kind == 9021 && it.pubkey !in members && it.pubkey !in resolvedRequestPubkeys }
                .filter { req -> val leave = lastLeave[req.pubkey]; leave == null || req.createdAt > leave }
                .distinctBy { it.pubkey }
                .sortedByDescending { it.createdAt }
        }
    }

    // User is "pending approval" if marked as joined (sent 9021) but not in the
    // authoritative member list from the relay (kind 39002).
    // Simple check: joined + not in members = pending. No hasJoinRequest gate,
    // so this triggers immediately after Join click (no relay echo delay).
    val isPendingApproval by remember(groupId) {
        derivedStateOf {
            val joined = joinedGroups.contains(groupId)
            val pubkey = currentUserPubkey
            if (!joined || pubkey == null) return@derivedStateOf false
            val k39002 = allGroupMembers[groupId] ?: emptyList()
            val isClosed = groups.find { it.id == groupId }?.isOpen == false
            when {
                k39002.isNotEmpty() -> pubkey !in k39002
                isClosed -> true // closed group, no member list yet → assume pending
                else -> false    // open group, no list yet → don't block
            }
        }
    }


    // Derive invite codes from kind 9009 events, excluding revoked ones (kind 9005 with "e" tag).
    val inviteCodes by remember(groupId) {
        derivedStateOf {
            val msgs = allMessages[groupId] ?: emptyList()
            val revokedEventIds = msgs
                .filter { it.kind == 9005 }
                .flatMap { msg -> msg.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) } }
                .toSet()
            msgs
                .filter { it.kind == 9009 }
                .mapNotNull { msg ->
                    val code = msg.tags.firstOrNull { it.firstOrNull() == "code" }?.getOrNull(1) ?: return@mapNotNull null
                    if (msg.id in revokedEventIds) return@mapNotNull null
                    InviteCode(code = code, createdAt = msg.createdAt, eventId = msg.id)
                }
                .sortedByDescending { it.createdAt }
        }
    }

    // Determine recently active members (messaged in last 10 minutes)
    val recentlyActiveMembers = remember(messages) {
        val tenMinutesAgo = epochSeconds() - (10 * 60)
        messages
            .filter { it.createdAt >= tenMinutesAgo }
            .map { it.pubkey }
            .toSet()
    }

    val connectionStatus = when (connectionState) {
        is ConnectionManager.ConnectionState.Disconnected -> "Disconnected"
        is ConnectionManager.ConnectionState.Connecting -> "Connecting..."
        is ConnectionManager.ConnectionState.Connected -> "Connected"
        is ConnectionManager.ConnectionState.Reconnecting -> {
            val state = connectionState as ConnectionManager.ConnectionState.Reconnecting
            "Reconnecting (${state.attempt}/${state.maxAttempts})..."
        }
        is ConnectionManager.ConnectionState.Error -> "Connection lost"
    }

    val chatItems = remember(messages) {
        buildChatItems(messages)
    }

    val isInitialLoading = isLoadingMoreMap[groupId] == true && chatItems.isEmpty()
    val isMembersLoading = groupId in loadingMembersSet && groupMembers.isEmpty()

    LaunchedEffect(groupId) {
        vm.requestGroupMessages(selectedChannel)
    }

    LaunchedEffect(selectedChannel) {
        vm.requestGroupMessages(selectedChannel)
    }

    // Re-request messages when connection is restored so the open group reloads after
    // a reconnect, even if it wasn't in the joined list or messages cache.
    LaunchedEffect(connectionState) {
        if (connectionState is ConnectionManager.ConnectionState.Connected) {
            vm.requestGroupMessages(selectedChannel)
        }
    }

    // Group info modal
    if (showGroupInfoModal) {
        GroupInfoModal(
            groupId = groupId,
            groupName = groupName,
            groupMetadata = currentGroupMetadata,
            userMetadata = userMetadata,
            onUserClick = { pubkey ->
                showGroupInfoModal = false
                selectedUserPubkey = pubkey
            },
            onDismiss = { showGroupInfoModal = false }
        )
    }

    // Edit group modal (admin only)
    if (showEditGroupModal) {
        EditGroupModal(
            groupId = groupId,
            currentMetadata = currentGroupMetadata,
            members = groupMembers,
            currentUserPubkey = currentUserPubkey,
            onPromoteToAdmin = { pubkey -> vm.promoteToAdmin(pubkey) },
            onDemoteFromAdmin = { pubkey -> vm.demoteFromAdmin(pubkey) },
            onRemoveMember = { member -> vm.removeUser(member.pubkey) },
            onDismiss = { showEditGroupModal = false },
            onGroupUpdated = { showEditGroupModal = false }
        )
    }

    // Invite codes modal (admin + closed groups)
    if (showInviteCodesModal) {
        InviteCodesModal(
            inviteCodes = inviteCodes,
            onCreateInviteCode = {
                vm.createInviteCode { code -> createdInviteCode = code }
            },
            onRevokeInviteCode = { eventId -> vm.revokeInviteCode(eventId) },
            onDismiss = {
                showInviteCodesModal = false
                createdInviteCode = null
                if (moderationError != null) vm.clearModerationError()
            },
            relayUrl = currentRelayUrl,
            groupId = groupId,
            createdCode = createdInviteCode,
            errorMessage = moderationError,
            onClearError = { vm.clearModerationError() }
        )
    }

    // Delete group confirmation dialog (admin only)
    if (showDeleteGroupDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteGroupDialog = false },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Delete Group") },
            text = { Text("Are you sure you want to permanently delete \"${currentGroupMetadata?.name ?: groupName ?: "this group"}\"? This action cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.deleteGroup {
                            showDeleteGroupDialog = false
                            onNavigateHome()
                        }
                    }
                ) {
                    Text("Delete", color = NostrordColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteGroupDialog = false }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            }
        )
    }

    // Delete message confirmation dialog
    messageToDelete?.let { msg ->
        AlertDialog(
            onDismissRequest = { messageToDelete = null },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Delete Message") },
            text = { Text("Are you sure you want to delete this message? This action cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMessage(msg.id)
                    messageToDelete = null
                }) {
                    Text("Delete", color = NostrordColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { messageToDelete = null }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            }
        )
    }

    // Delete message error dialog (relay rejected the deletion)
    deleteMessageError?.let { error ->
        AlertDialog(
            onDismissRequest = { vm.clearDeleteMessageError() },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Could Not Delete Message") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { vm.clearDeleteMessageError() }) {
                    Text("OK", color = NostrordColors.Primary)
                }
            }
        )
    }

    // Reaction error dialog (relay rejected kind 7)
    reactionError?.let { error ->
        val isUnknownMember = error.contains("unknown member", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { vm.clearReactionError() },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text(if (isUnknownMember) "Join Required" else "Cannot React") },
            text = {
                Text(
                    if (isUnknownMember) "You need to join this group before you can react to messages."
                    else "This relay does not support reactions.\n\n$error"
                )
            },
            confirmButton = {
                if (isUnknownMember) {
                    TextButton(onClick = {
                        vm.clearReactionError()
                        if (currentGroupMetadata?.isOpen == false) {
                            // Closed group — show invite code modal instead of direct join
                        } else {
                            vm.joinGroup()
                        }
                    }) {
                        Text("Join Group", color = NostrordColors.Primary)
                    }
                } else {
                    TextButton(onClick = { vm.clearReactionError() }) {
                        Text("OK", color = NostrordColors.Primary)
                    }
                }
            },
            dismissButton = if (isUnknownMember) {
                { TextButton(onClick = { vm.clearReactionError() }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                } }
            } else null
        )
    }

    // Send message error dialog
    sendError?.let { error ->
        val isPendingError = error.contains("pending admin approval", ignoreCase = true)
        AlertDialog(
            onDismissRequest = { vm.clearSendError() },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text(if (isPendingError) "Pending Approval" else "Message Not Sent") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { vm.clearSendError() }) {
                    Text("OK", color = NostrordColors.Primary)
                }
            }
        )
    }

    // User profile modal
    selectedUserPubkey?.let { pubkey ->
        UserProfileModal(
            pubkey = pubkey,
            metadata = userMetadata[pubkey],
            userMetadata = userMetadata,
            onUserClick = { clickedPubkey ->
                selectedUserPubkey = clickedPubkey
            },
            onDismiss = { selectedUserPubkey = null }
        )
    }

    // Remove member confirmation dialog
    memberToRemove?.let { member ->
        AlertDialog(
            onDismissRequest = { memberToRemove = null },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Remove Member") },
            text = { Text("Remove ${member.displayName} from this group?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.removeUser(member.pubkey)
                        memberToRemove = null
                    }
                ) {
                    Text("Remove", color = NostrordColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToRemove = null }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            }
        )
    }

    // Join requests modal (admin only)
    if (showJoinRequestsModal) {
        JoinRequestsModal(
            pendingRequests = pendingJoinRequests,
            userMetadata = userMetadata,
            onApprove = { pubkey: String ->
                vm.approveJoinRequest(pubkey)
                resolvedRequestPubkeys = resolvedRequestPubkeys + pubkey
            },
            onReject = { eventId: String ->
                val pubkey = pendingJoinRequests.find { it.id == eventId }?.pubkey
                vm.rejectJoinRequest(eventId)
                if (pubkey != null) resolvedRequestPubkeys = resolvedRequestPubkeys + pubkey
            },
            onDismiss = { showJoinRequestsModal = false }
        )
    }

    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Leave Group") },
            text = { Text("Are you sure you want to leave ${groupName ?: "this group"}? You can rejoin later if you change your mind.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.leaveGroup {
                            showLeaveDialog = false
                            onNavigateHome()
                        }
                    }
                ) {
                    Text("Leave", color = NostrordColors.Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveDialog = false }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            }
        )
    }

    // Responsive layout
    val parentHidden = LocalAnimatedImageHidden.current
    val anyDialogOpen = parentHidden || showLeaveDialog || showGroupInfoModal || showEditGroupModal ||
        showDeleteGroupDialog || messageToDelete != null || selectedUserPubkey != null || showMemberSheet || memberToRemove != null || showJoinRequestsModal || showInviteCodesModal
    CompositionLocalProvider(LocalAnimatedImageHidden provides anyDialogOpen) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = !forceDesktop

        if (isCompact) {
            GroupScreenMobile(
                groupId = groupId,
                groupName = groupName,
                groupMetadata = currentGroupMetadata,
                selectedChannel = selectedChannel,
                onChannelSelect = { selectedChannel = it },
                onOpenDrawer = onOpenDrawer,
                messages = messages,
                chatItems = chatItems,
                connectionStatus = connectionStatus,
                connectionState = connectionState,
                isJoined = isJoined,
                isAdmin = isAdmin,
                userMetadata = userMetadata,
                reactions = allReactions,
                currentUserPubkey = currentUserPubkey,
                messageInput = messageInput,
                onMessageInputChange = { messageInput = it },
                onSendMessage = {
                    val imetaTags = pendingUploads.map { it.toImetaTag() }
                    vm.sendMessage(messageInput, selectedChannel, mentions, replyingToMessage?.id, imetaTags)
                    messageInput = ""
                    mentions = emptyMap()
                    replyingToMessage = null
                    pendingUploads = emptyList()
                },
                onJoinGroup = { inviteCode -> vm.joinGroup(inviteCode) },
                onLeaveGroup = { showLeaveDialog = true },
                onShowGroupInfo = { showGroupInfoModal = true },
                onEditGroup = { showEditGroupModal = true },
                onDeleteGroup = { showDeleteGroupDialog = true },
                groupMembers = groupMembers,
                recentlyActiveMembers = recentlyActiveMembers,
                mentions = mentions,
                onMentionsChange = { mentions = it },
                replyingToMessage = replyingToMessage,
                onReplyClick = { message -> replyingToMessage = message },
                onDeleteMessage = { message -> messageToDelete = message },
                onReactionBadgeClick = { messageId, emoji ->
                    val targetMessage = messages.find { it.id == messageId }
                    if (targetMessage != null) {
                        vm.sendReaction(messageId, targetMessage.pubkey, emoji)
                    }
                },
                onCancelReply = { replyingToMessage = null },
                isMembersLoading = isMembersLoading,
                isInitialLoading = isInitialLoading,
                isLoadingMore = isLoadingMore,
                hasMoreMessages = hasMoreMessages,
                onLoadMore = { vm.loadMoreMessages(selectedChannel) },
                joinedGroups = joinedGroups,
                groups = groups,
                onNavigateToGroup = onNavigateToGroup,
                onSwitchRelay = { vm.switchRelay(it) },
                onUserClick = { pubkey -> selectedUserPubkey = pubkey },
                onReconnect = { vm.reconnect() },
                isSending = isSending,
                onMediaUploaded = { upload ->
                        if (pendingUploads.none { it.url == upload.url }) {
                            pendingUploads = pendingUploads + upload
                        }
                    },
                isCurrentUserAdmin = isAdmin,
                onRemoveMember = { member -> memberToRemove = member },
                onAddMember = { pubkey -> vm.addUser(pubkey) },
                pendingJoinRequestCount = pendingJoinRequests.size,
                onJoinRequestsClick = { showJoinRequestsModal = true },
                isPendingApproval = isPendingApproval,
                onInviteCodesClick = { showInviteCodesModal = true },
                isClosed = currentGroupMetadata?.isOpen == false,
                initialInviteCode = initialInviteCode
            )
        } else {
            GroupScreenDesktop(
                groupId = groupId,
                groupName = groupName,
                groupMetadata = currentGroupMetadata,
                selectedChannel = selectedChannel,
                onChannelSelect = { selectedChannel = it },
                messages = messages,
                chatItems = chatItems,
                connectionStatus = connectionStatus,
                connectionState = connectionState,
                isJoined = isJoined,
                isAdmin = isAdmin,
                userMetadata = userMetadata,
                reactions = allReactions,
                currentUserPubkey = currentUserPubkey,
                messageInput = messageInput,
                onMessageInputChange = { messageInput = it },
                onSendMessage = {
                    val imetaTags = pendingUploads.map { it.toImetaTag() }
                    vm.sendMessage(messageInput, selectedChannel, mentions, replyingToMessage?.id, imetaTags)
                    messageInput = ""
                    mentions = emptyMap()
                    replyingToMessage = null
                    pendingUploads = emptyList()
                },
                onJoinGroup = { inviteCode -> vm.joinGroup(inviteCode) },
                onLeaveGroup = { showLeaveDialog = true },
                onShowGroupInfo = { showGroupInfoModal = true },
                onEditGroup = { showEditGroupModal = true },
                onDeleteGroup = { showDeleteGroupDialog = true },
                groupMembers = groupMembers,
                recentlyActiveMembers = recentlyActiveMembers,
                mentions = mentions,
                onMentionsChange = { mentions = it },
                replyingToMessage = replyingToMessage,
                onReplyClick = { message -> replyingToMessage = message },
                onDeleteMessage = { message -> messageToDelete = message },
                onReactionBadgeClick = { messageId, emoji ->
                    val targetMessage = messages.find { it.id == messageId }
                    if (targetMessage != null) {
                        vm.sendReaction(messageId, targetMessage.pubkey, emoji)
                    }
                },
                onCancelReply = { replyingToMessage = null },
                isMembersLoading = isMembersLoading,
                isInitialLoading = isInitialLoading,
                isLoadingMore = isLoadingMore,
                hasMoreMessages = hasMoreMessages,
                onLoadMore = { vm.loadMoreMessages(selectedChannel) },
                joinedGroups = joinedGroups,
                groups = groups,
                onNavigateToGroup = onNavigateToGroup,
                onSwitchRelay = { vm.switchRelay(it) },
                onUserClick = { pubkey -> selectedUserPubkey = pubkey },
                onReconnect = { vm.reconnect() },
                isSending = isSending,
                onMediaUploaded = { upload ->
                        if (pendingUploads.none { it.url == upload.url }) {
                            pendingUploads = pendingUploads + upload
                        }
                    },
                showMemberSidebar = maxWidth >= 1080.dp,
                showMemberSheet = showMemberSheet,
                onShowMemberSheet = { showMemberSheet = it },
                isCurrentUserAdmin = isAdmin,
                onRemoveMember = { member -> memberToRemove = member },
                onAddMember = { pubkey -> vm.addUser(pubkey) },
                pendingJoinRequestCount = pendingJoinRequests.size,
                onJoinRequestsClick = { showJoinRequestsModal = true },
                isPendingApproval = isPendingApproval,
                onInviteCodesClick = { showInviteCodesModal = true },
                isClosed = currentGroupMetadata?.isOpen == false,
                initialInviteCode = initialInviteCode
            )
        }
    }
    } // CompositionLocalProvider
}
