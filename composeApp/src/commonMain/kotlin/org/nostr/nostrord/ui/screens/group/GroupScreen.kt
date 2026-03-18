package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.utils.epochSeconds
import org.nostr.nostrord.network.managers.ConnectionManager
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
    showServerRail: Boolean = true // When false, server rail is handled by parent shell
) {
    val scope = rememberCoroutineScope()

    var selectedChannel by remember { mutableStateOf("general") }

    val allMessages by NostrRepository.messages.collectAsState()
    val allGroupMessages = allMessages[groupId] ?: emptyList()

    val messages = remember(allGroupMessages, selectedChannel) {
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

    val connectionState by NostrRepository.connectionState.collectAsState()
    val joinedGroups by NostrRepository.joinedGroups.collectAsState()
    val groups by NostrRepository.groups.collectAsState()
    val userMetadata by NostrRepository.userMetadata.collectAsState()
    val allReactions by NostrRepository.reactions.collectAsState()
    val allGroupMembers by NostrRepository.groupMembers.collectAsState()
    val allGroupAdmins by NostrRepository.groupAdmins.collectAsState()
    val currentUserPubkey = NostrRepository.getPublicKey()

    // Get current group metadata
    val currentGroupMetadata = remember(groups, groupId) {
        groups.find { it.id == groupId }
    }

    // Admin detection: check if current user is in kind:39001 admins list
    val isAdmin = remember(allGroupAdmins, groupId, currentUserPubkey) {
        currentUserPubkey != null && currentUserPubkey in (allGroupAdmins[groupId] ?: emptyList())
    }

    // Get members from kind 39002 for this group
    val kind39002Members = allGroupMembers[groupId] ?: emptyList()

    // Pagination state
    val isLoadingMoreMap by NostrRepository.isLoadingMore.collectAsState()
    val hasMoreMessagesMap by NostrRepository.hasMoreMessages.collectAsState()
    val isLoadingMore = isLoadingMoreMap[groupId] ?: false
    val hasMoreMessages = hasMoreMessagesMap[groupId] ?: true

    var messageInput by remember { mutableStateOf("") }
    var mentions by remember { mutableStateOf<Map<String, String>>(emptyMap()) } // displayName -> pubkey
    var replyingToMessage by remember { mutableStateOf<NostrGroupClient.NostrMessage?>(null) }
    var showLeaveDialog by remember { mutableStateOf(false) }
    var showGroupInfoModal by remember { mutableStateOf(false) }
    var showEditGroupModal by remember { mutableStateOf(false) }
    var showDeleteGroupDialog by remember { mutableStateOf(false) }
    var selectedUserPubkey by remember { mutableStateOf<String?>(null) }
    val isJoined = joinedGroups.contains(groupId)

    // Use kind 39002 members if available, otherwise fall back to message-based members
    val groupMembers = remember(kind39002Members, allGroupMessages, userMetadata) {
        // Prefer kind 39002 members, fall back to message pubkeys
        val memberPubkeys = if (kind39002Members.isNotEmpty()) {
            kind39002Members
        } else {
            allGroupMessages.map { it.pubkey }.distinct()
        }

        memberPubkeys
            .map { pubkey ->
                val metadata = userMetadata[pubkey]
                MemberInfo(
                    pubkey = pubkey,
                    displayName = metadata?.displayName
                        ?: metadata?.name
                        ?: pubkey.take(8) + "...",
                    picture = metadata?.picture
                )
            }
            .sortedBy { it.displayName.lowercase() }
    }

    // Determine recently active members (messaged in last 10 minutes)
    val recentlyActiveMembers = remember(allGroupMessages) {
        val tenMinutesAgo = epochSeconds() - (10 * 60)
        allGroupMessages
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

    LaunchedEffect(groupId) {
        scope.launch {
            NostrRepository.requestGroupMessages(groupId, selectedChannel)
        }
    }

    LaunchedEffect(selectedChannel) {
        scope.launch {
            NostrRepository.requestGroupMessages(groupId, selectedChannel)
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
                        scope.launch {
                            NostrRepository.deleteGroup(groupId)
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
                        scope.launch {
                            NostrRepository.leaveGroup(groupId)
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
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp

        if (isCompact) {
            GroupScreenMobile(
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
                    scope.launch {
                        NostrRepository.sendMessage(groupId, messageInput, selectedChannel, mentions, replyingToMessage?.id)
                        messageInput = ""
                        mentions = emptyMap()
                        replyingToMessage = null
                    }
                },
                onJoinGroup = {
                    scope.launch { NostrRepository.joinGroup(groupId) }
                },
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
                onCancelReply = { replyingToMessage = null },
                isLoadingMore = isLoadingMore,
                hasMoreMessages = hasMoreMessages,
                onLoadMore = {
                    scope.launch { NostrRepository.loadMoreMessages(groupId, selectedChannel) }
                },
                joinedGroups = joinedGroups,
                groups = groups,
                onNavigateToGroup = onNavigateToGroup,
                onUserClick = { pubkey -> selectedUserPubkey = pubkey },
                onReconnect = { scope.launch { NostrRepository.reconnect() } }
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
                    scope.launch {
                        NostrRepository.sendMessage(groupId, messageInput, selectedChannel, mentions, replyingToMessage?.id)
                        messageInput = ""
                        mentions = emptyMap()
                        replyingToMessage = null
                    }
                },
                onJoinGroup = {
                    scope.launch { NostrRepository.joinGroup(groupId) }
                },
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
                onCancelReply = { replyingToMessage = null },
                isLoadingMore = isLoadingMore,
                hasMoreMessages = hasMoreMessages,
                onLoadMore = {
                    scope.launch { NostrRepository.loadMoreMessages(groupId, selectedChannel) }
                },
                joinedGroups = joinedGroups,
                groups = groups,
                onNavigateToGroup = onNavigateToGroup,
                onUserClick = { pubkey -> selectedUserPubkey = pubkey },
                onReconnect = { scope.launch { NostrRepository.reconnect() } }
            )
        }
    }
}
