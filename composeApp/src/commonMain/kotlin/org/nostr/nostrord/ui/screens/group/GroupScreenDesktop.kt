package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
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
    groupMetadata: GroupMetadata?,
    selectedChannel: String,
    onChannelSelect: (String) -> Unit,
    messages: List<NostrGroupClient.NostrMessage>,
    chatItems: List<ChatItem>,
    connectionStatus: String,
    connectionState: ConnectionManager.ConnectionState,
    isJoined: Boolean,
    userMetadata: Map<String, UserMetadata>,
    messageInput: String,
    onMessageInputChange: (String) -> Unit,
    onSendMessage: () -> Unit,
    onJoinGroup: () -> Unit,
    onLeaveGroup: () -> Unit,
    onBack: () -> Unit,
    onShowGroupInfo: () -> Unit = {},
    groupMembers: List<MemberInfo> = emptyList(),
    recentlyActiveMembers: Set<String> = emptySet(),
    mentions: Map<String, String> = emptyMap(),
    onMentionsChange: (Map<String, String>) -> Unit = {},
    isLoadingMore: Boolean = false,
    hasMoreMessages: Boolean = true,
    onLoadMore: () -> Unit = {},
    joinedGroups: Set<String> = emptySet(),
    groups: List<GroupMetadata> = emptyList(),
    onNavigateToGroup: (groupId: String, groupName: String?) -> Unit = { _, _ -> },
    onUserClick: (String) -> Unit = {}
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Main content area (messages)
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(NostrordColors.Background)
        ) {
            // Header with group info and actions
            GroupHeader(
                groupName = groupName,
                groupMetadata = groupMetadata,
                isJoined = isJoined,
                onBackClick = onBack,
                onJoinClick = onJoinGroup,
                onLeaveClick = onLeaveGroup,
                onTitleClick = onShowGroupInfo
            )

            // Messages area (fills remaining space)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                MessagesList(
                    chatItems = chatItems,
                    userMetadata = userMetadata,
                    isJoined = isJoined,
                    isLoadingMore = isLoadingMore,
                    hasMoreMessages = hasMoreMessages,
                    onLoadMore = onLoadMore,
                    onUsernameClick = onUserClick
                )
            }

            // Message input
            MessageInput(
                isJoined = isJoined,
                selectedChannel = selectedChannel,
                messageInput = messageInput,
                onMessageInputChange = onMessageInputChange,
                onSendMessage = onSendMessage,
                onJoinGroup = onJoinGroup,
                groupMembers = groupMembers,
                mentions = mentions,
                onMentionsChange = onMentionsChange
            )
        }

        // Member sidebar (240dp fixed width)
        MemberSidebar(
            members = groupMembers,
            recentlyActiveMembers = recentlyActiveMembers,
            onMemberClick = { member -> onUserClick(member.pubkey) }
        )
    }
}
