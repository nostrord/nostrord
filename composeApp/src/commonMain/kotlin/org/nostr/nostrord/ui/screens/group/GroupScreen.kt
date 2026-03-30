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
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.ui.screens.group.components.EditGroupModal
import org.nostr.nostrord.ui.screens.group.components.GroupInfoModal
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
    onOpenDrawer: () -> Unit = {}
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
    val connectionState by vm.connectionState.collectAsState()
    val joinedGroups by vm.joinedGroups.collectAsState()
    val groups by vm.groups.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()
    val allReactions by vm.reactions.collectAsState()
    val allGroupMembers by vm.groupMembers.collectAsState()
    val allGroupAdmins by vm.groupAdmins.collectAsState()
    val loadingMembersSet by vm.loadingMembers.collectAsState()
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
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showGroupInfoModal by remember { mutableStateOf(false) }
    var showEditGroupModal by remember { mutableStateOf(false) }
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    var messageToDelete by remember { mutableStateOf<NostrGroupClient.NostrMessage?>(null) }
    var selectedUserPubkey by remember { mutableStateOf<String?>(null) }
    val isJoined = joinedGroups.contains(groupId)

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
    val groupMembers by remember(groupId) {
        derivedStateOf {
            memberPubkeys.map { pubkey ->
                val metadata = userMetadata[pubkey]
                MemberInfo(
                    pubkey = pubkey,
                    displayName = metadata?.displayName
                        ?: metadata?.name
                        ?: pubkey.take(8) + "...",
                    picture = metadata?.picture
                )
            }.sortedBy { it.displayName.lowercase() }
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
            onDismiss = { showGroupInfoModal = false }
        )
    }

    // Edit group modal (admin only)
    if (showEditGroupModal) {
        EditGroupModal(
            groupId = groupId,
            currentMetadata = currentGroupMetadata,
            onDismiss = { showEditGroupModal = false },
            onGroupUpdated = { showEditGroupModal = false }
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
                        vm.joinGroup()
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
        AlertDialog(
            onDismissRequest = { vm.clearSendError() },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Message Not Sent") },
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
            onDismiss = { selectedUserPubkey = null }
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
        showDeleteGroupDialog || messageToDelete != null || selectedUserPubkey != null
    CompositionLocalProvider(LocalAnimatedImageHidden provides anyDialogOpen) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp

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
                    vm.sendMessage(messageInput, selectedChannel, mentions, replyingToMessage?.id)
                    messageInput = ""
                    mentions = emptyMap()
                    replyingToMessage = null
                },
                onJoinGroup = { vm.joinGroup() },
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
                isSending = isSending
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
                    vm.sendMessage(messageInput, selectedChannel, mentions, replyingToMessage?.id)
                    messageInput = ""
                    mentions = emptyMap()
                    replyingToMessage = null
                },
                onJoinGroup = { vm.joinGroup() },
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
                isSending = isSending
            )
        }
    }
    } // CompositionLocalProvider
}
