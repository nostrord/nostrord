package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.ConnectionStatusBanner
import org.nostr.nostrord.ui.components.sidebars.MemberSidebar
import org.nostr.nostrord.ui.screens.group.components.GroupHeader
import org.nostr.nostrord.ui.screens.group.components.MessageInput
import org.nostr.nostrord.ui.screens.group.components.MessagesList
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Desktop group screen layout.
 *
 * Two-column layout (when inside DesktopShell with ServerRail):
 * ┌───────────────────────┬──────────────┐
 * │       Messages        │    Members   │
 * │                       │    Sidebar   │
 * │       (flex)          │    (240dp)   │
 * └───────────────────────┴──────────────┘
 *
 * Note: ServerRail (72dp) is handled by DesktopShell wrapper,
 * not this component.
 */
@Composable
fun GroupScreenDesktop(
    groupId: String,
    groupName: String?,
    relayUrl: String = "",
    groupMetadata: GroupMetadata?,
    selectedChannel: String,
    onChannelSelect: (String) -> Unit,
    messages: List<NostrGroupClient.NostrMessage>,
    chatItems: List<ChatItem>,
    connectionStatus: String,
    connectionState: ConnectionManager.ConnectionState,
    isJoined: Boolean,
    isAdmin: Boolean = false,
    userMetadata: Map<String, UserMetadata>,
    reactions: Map<String, Map<String, GroupManager.ReactionInfo>> = emptyMap(),
    currentUserPubkey: String? = null,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onJoinGroup: (inviteCode: String?) -> Unit,
    onLeaveGroup: () -> Unit,
    onShowGroupInfo: () -> Unit = {},
    onEditGroup: () -> Unit = {},
    onDeleteGroup: () -> Unit = {},
    onManageMembers: () -> Unit = {},
    groupMembers: List<MemberInfo> = emptyList(),
    recentlyActiveMembers: Set<String> = emptySet(),
    mentions: Map<String, String> = emptyMap(),
    onMentionsChange: (Map<String, String>) -> Unit = {},
    replyingToMessage: NostrMessage? = null,
    onReplyClick: (NostrMessage) -> Unit = {},
    onDeleteMessage: (NostrMessage) -> Unit = {},
    onReactionBadgeClick: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onCancelReply: () -> Unit = {},
    isMembersLoading: Boolean = false,
    isInitialLoading: Boolean = false,
    isLoadingMore: Boolean = false,
    hasMoreMessages: Boolean = true,
    onLoadMore: () -> Unit = {},
    joinedGroups: Set<String> = emptySet(),
    groups: List<GroupMetadata> = emptyList(),
    onNavigateToGroup: (groupId: String, groupName: String?) -> Unit = { _, _ -> },
    onSwitchRelay: (String) -> Unit = {},
    onUserClick: (String) -> Unit = {},
    onReconnect: () -> Unit = {},
    isSending: Boolean = false,
    onMediaUploaded: (org.nostr.nostrord.network.upload.UploadResult) -> Unit = {},
    showMemberSidebar: Boolean = true,
    showMemberSheet: Boolean = false,
    onShowMemberSheet: (Boolean) -> Unit = {},
    isCurrentUserAdmin: Boolean = false,
    onRemoveMember: (MemberInfo) -> Unit = {},
    onAddMember: (String) -> Unit = {},
    pendingJoinRequestCount: Int = 0,
    onJoinRequestsClick: () -> Unit = {},
    isPendingApproval: Boolean = false,
    onInviteCodesClick: () -> Unit = {},
    isClosed: Boolean = false,
    isGroupRestricted: Boolean = false,
    initialInviteCode: String? = null
) {

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(NostrordColors.Background)
        ) {
            GroupHeader(
                groupName = if (isGroupRestricted && groupName == null) "Private Group" else groupName,
                groupMetadata = groupMetadata,
                relayUrl = relayUrl,
                groupId = groupId,
                isJoined = isJoined,
                isAdmin = isAdmin,
                onJoinClick = onJoinGroup,
                onLeaveClick = onLeaveGroup,
                onTitleClick = onShowGroupInfo,
                onEditClick = onEditGroup,
                onDeleteClick = onDeleteGroup,
                onManageMembersClick = onManageMembers,
                onInviteCodesClick = onInviteCodesClick,
                isClosed = isClosed,
                initialInviteCode = initialInviteCode,
                pendingJoinRequestCount = pendingJoinRequestCount,
                onJoinRequestsClick = onJoinRequestsClick,
                trailingIcon = if (!showMemberSidebar) {
                    {
                        IconButton(
                            onClick = { onShowMemberSheet(true) },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = "Members",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else null
            )

            ConnectionStatusBanner(
                connectionState = connectionState,
                onRetry = onReconnect
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                MessagesList(
                    groupId = groupId,
                    chatItems = chatItems,
                    messages = messages,
                    userMetadata = userMetadata,
                    reactions = reactions,
                    currentUserPubkey = currentUserPubkey,
                    isJoined = isJoined,
                    isInitialLoading = isInitialLoading,
                    isLoadingMore = isLoadingMore,
                    hasMoreMessages = hasMoreMessages,
                    onLoadMore = onLoadMore,
                    onUsernameClick = onUserClick,
                    onReplyClick = onReplyClick,
                    onDeleteMessage = onDeleteMessage,
                    onReactionBadgeClick = onReactionBadgeClick,
                    onNavigateToGroup = { targetGroupId, targetGroupName, targetRelayUrl ->
                        if (targetRelayUrl != null) onSwitchRelay(targetRelayUrl)
                        onNavigateToGroup(targetGroupId, targetGroupName)
                    }
                )
            }

            MessageInput(
                isPendingApproval = isPendingApproval,
                isJoined = isJoined,
                selectedChannel = selectedChannel,
                groupName = groupName,
                messageInput = messageInput,
                onMessageInputChange = onMessageInputChange,
                onSendMessage = onSendMessage,
                onJoinGroup = onJoinGroup,
                groupMembers = groupMembers,
                mentions = mentions,
                onMentionsChange = onMentionsChange,
                replyingToMessage = replyingToMessage,
                replyingToMetadata = replyingToMessage?.let { userMetadata[it.pubkey] },
                userMetadata = userMetadata,
                onCancelReply = onCancelReply,
                isSending = isSending,
                onMediaUploaded = onMediaUploaded
            )
        }

        if (showMemberSidebar) {
            MemberSidebar(
                members = groupMembers,
                recentlyActiveMembers = recentlyActiveMembers,
                isLoading = isMembersLoading,
                onMemberClick = { member -> onUserClick(member.pubkey) },
                isCurrentUserAdmin = isCurrentUserAdmin,
                currentUserPubkey = currentUserPubkey,
                onRemoveMember = onRemoveMember,
                onAddMember = onAddMember
            )
        }
    }

    if (showMemberSheet && !showMemberSidebar) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { onShowMemberSheet(false) },
            sheetState = rememberModalBottomSheetState(),
            containerColor = NostrordColors.Surface,
            sheetMaxWidth = Dp.Unspecified
        ) {
            MemberSidebar(
                members = groupMembers,
                recentlyActiveMembers = recentlyActiveMembers,
                isLoading = isMembersLoading,
                onMemberClick = { member ->
                    onShowMemberSheet(false)
                    onUserClick(member.pubkey)
                },
                isCurrentUserAdmin = isCurrentUserAdmin,
                currentUserPubkey = currentUserPubkey,
                onRemoveMember = onRemoveMember,
                onAddMember = onAddMember,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
