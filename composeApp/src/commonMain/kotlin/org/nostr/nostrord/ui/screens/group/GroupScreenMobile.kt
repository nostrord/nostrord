package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.components.navigation.GroupQuickSwitchBarCompact
import org.nostr.nostrord.ui.components.sidebars.GroupSidebar
import org.nostr.nostrord.ui.components.sidebars.MemberSidebar
import org.nostr.nostrord.ui.screens.group.components.MessageInput
import org.nostr.nostrord.ui.screens.group.components.MessagesList
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupScreenMobile(
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
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showMemberSheet by remember { mutableStateOf(false) }
    val memberSheetState = rememberModalBottomSheetState()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = NostrordColors.Surface
            ) {
                GroupSidebar(
                    groupName = groupName,
                    selectedId = selectedChannel,
                    onSelect = { channel ->
                        scope.launch { drawerState.close() }
                        onChannelSelect(channel)
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("#$selectedChannel", color = Color.White, fontWeight = FontWeight.Bold)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Connection status dot
                                val statusColor = when (connectionState) {
                                    is ConnectionManager.ConnectionState.Connected -> NostrordColors.Success
                                    is ConnectionManager.ConnectionState.Connecting -> NostrordColors.Warning
                                    is ConnectionManager.ConnectionState.Disconnected -> NostrordColors.TextMuted
                                    is ConnectionManager.ConnectionState.Error -> NostrordColors.Error
                                }
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(statusColor)
                                )
                                Text(
                                    text = connectionStatus,
                                    color = NostrordColors.TextMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Text(
                                    text = "•",
                                    color = NostrordColors.TextMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                                Icon(
                                    Icons.Default.People,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = NostrordColors.TextMuted
                                )
                                Text(
                                    text = "${groupMembers.size}",
                                    color = NostrordColors.TextMuted,
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        Row {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = Color.White)
                            }
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Default.Menu, contentDescription = "Channels", tint = Color.White)
                            }
                        }
                    },
                    actions = {
                        // Members button
                        IconButton(onClick = { showMemberSheet = true }) {
                            Icon(Icons.Default.People, contentDescription = "Members", tint = Color.White)
                        }
                        if (!isJoined) {
                            TextButton(onClick = onJoinGroup) {
                                Text("Join", color = NostrordColors.Primary)
                            }
                        } else {
                            TextButton(onClick = onLeaveGroup) {
                                Text("Leave", color = NostrordColors.Error)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NostrordColors.BackgroundDark
                    )
                )
            },
            containerColor = NostrordColors.Background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Quick-switch bar for navigating between groups
                if (joinedGroups.isNotEmpty()) {
                    GroupQuickSwitchBarCompact(
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
        }
    }

    // Member list bottom sheet
    if (showMemberSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMemberSheet = false },
            sheetState = memberSheetState,
            containerColor = NostrordColors.Surface
        ) {
            MemberSidebar(
                members = groupMembers,
                recentlyActiveMembers = recentlyActiveMembers,
                onMemberClick = { /* TODO: Show member profile */ },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
