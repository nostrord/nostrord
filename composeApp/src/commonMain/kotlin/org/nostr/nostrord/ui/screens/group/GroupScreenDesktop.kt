package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.components.navigation.GroupQuickSwitchBar
import org.nostr.nostrord.ui.components.sidebars.GroupSidebar
import org.nostr.nostrord.ui.components.sidebars.MemberSidebar
import org.nostr.nostrord.ui.screens.group.components.GroupHeader
import org.nostr.nostrord.ui.screens.group.components.MessageInput
import org.nostr.nostrord.ui.screens.group.components.MessagesList
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors

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
    groupMembers: List<MemberInfo> = emptyList(),
    recentlyActiveMembers: Set<String> = emptySet(),
    mentions: Map<String, String> = emptyMap(),
    onMentionsChange: (Map<String, String>) -> Unit = {},
    isLoadingMore: Boolean = false,
    hasMoreMessages: Boolean = true,
    onLoadMore: () -> Unit = {},
    joinedGroups: Set<String> = emptySet(),
    groups: List<GroupMetadata> = emptyList(),
    onNavigateToGroup: (groupId: String, groupName: String?) -> Unit = { _, _ -> }
) {
    Row(modifier = Modifier.fillMaxSize()) {
        GroupSidebar(
            groupName = groupName,
            selectedId = selectedChannel,
            onSelect = onChannelSelect
        )

        Column(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(NostrordColors.Background)
        ) {
            // Enhanced Header
            GroupHeader(
                selectedChannel = selectedChannel,
                groupMetadata = groupMetadata,
                connectionState = connectionState,
                memberCount = groupMembers.size,
                isJoined = isJoined,
                onBackClick = onBack,
                onJoinClick = onJoinGroup,
                onLeaveClick = onLeaveGroup
            )

            // Quick-switch bar for navigating between groups
            if (joinedGroups.isNotEmpty()) {
                GroupQuickSwitchBar(
                    joinedGroups = joinedGroups,
                    groups = groups,
                    activeGroupId = groupId,
                    onHomeClick = onBack,
                    onGroupClick = { newGroupId, newGroupName ->
                        if (newGroupId != groupId) {
                            onNavigateToGroup(newGroupId, newGroupName)
                        }
                    },
                    onExploreClick = onBack
                )
            }

            // Messages area
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
                    onLoadMore = onLoadMore
                )
            }

            // Input area
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

        // Member sidebar on the right
        MemberSidebar(
            members = groupMembers,
            recentlyActiveMembers = recentlyActiveMembers,
            onMemberClick = { /* TODO: Show member profile */ }
        )
    }
}
