package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.sidebars.MemberSidebar
import org.nostr.nostrord.ui.screens.group.components.MessageInput
import org.nostr.nostrord.ui.screens.group.components.MessagesList
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Mobile group screen with gesture navigation.
 *
 * Gesture support:
 * - Swipe left from right edge: Open member sheet
 * - Tap people icon: Open member sheet
 *
 * Layout follows mobile-first design:
 * - Full-screen message view
 * - Bottom-anchored input (thumb-reachable)
 * - Slide-in member panel
 */
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
    val scope = rememberCoroutineScope()
    var showMemberSheet by remember { mutableStateOf(false) }
    val memberSheetState = rememberModalBottomSheetState()

    // Gesture detection state
    var dragStartX by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            MobileGroupTopBar(
                groupName = groupName,
                groupMetadata = groupMetadata,
                isJoined = isJoined,
                onBackClick = onBack,
                onTitleClick = onShowGroupInfo,
                onMembersClick = { showMemberSheet = true },
                onJoinClick = onJoinGroup,
                onLeaveClick = onLeaveGroup
            )
        },
        containerColor = NostrordColors.Background
    ) { paddingValues ->
        // Main content with swipe gesture detection
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            dragStartX = offset.x
                            isDragging = true
                        },
                        onDragEnd = {
                            isDragging = false
                        },
                        onDragCancel = {
                            isDragging = false
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val screenWidth = size.width.toFloat()
                            val edgeThreshold = screenWidth * 0.15f // 15% of screen width
                            val swipeThreshold = 100f // Minimum drag distance

                            // Swipe left from right edge -> open member sheet
                            if (dragStartX > screenWidth - edgeThreshold && dragAmount < -swipeThreshold) {
                                showMemberSheet = true
                                isDragging = false
                            }
                        }
                    )
                }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Messages area (fills available space)
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

                // Input area (thumb-reachable at bottom)
                MessageInput(
                    isJoined = isJoined,
                    selectedChannel = selectedChannel,
                    groupName = groupName,
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
            containerColor = NostrordColors.Surface,
            shape = NostrordShapes.bottomSheetShape
        ) {
            MemberSidebar(
                members = groupMembers,
                recentlyActiveMembers = recentlyActiveMembers,
                onMemberClick = { member ->
                    showMemberSheet = false
                    onUserClick(member.pubkey)
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * Mobile-optimized top bar with proper touch targets (48dp minimum).
 * Follows desktop design with avatar and description.
 * Click on the title area to open group info modal.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileGroupTopBar(
    groupName: String?,
    groupMetadata: GroupMetadata?,
    isJoined: Boolean,
    onBackClick: () -> Unit,
    onTitleClick: () -> Unit,
    onMembersClick: () -> Unit,
    onJoinClick: () -> Unit,
    onLeaveClick: () -> Unit
) {
    TopAppBar(
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = Spacing.xs)
                    .clickable(onClick = onTitleClick)
            ) {
                // Group avatar
                ProfileAvatar(
                    imageUrl = groupMetadata?.picture,
                    displayName = groupName ?: "Group",
                    pubkey = groupMetadata?.id ?: "",
                    size = 32.dp
                )

                Spacer(modifier = Modifier.width(Spacing.sm))

                // Group name and description
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    // Group name
                    Text(
                        text = groupName ?: "Unknown Group",
                        style = NostrordTypography.ServerHeader,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Description (limited to prevent layout breaking)
                    if (!groupMetadata?.about.isNullOrBlank()) {
                        Text(
                            text = groupMetadata?.about ?: "",
                            style = NostrordTypography.Tiny,
                            color = NostrordColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        navigationIcon = {
            // Back button (48dp touch target)
            IconButton(
                onClick = onBackClick,
                modifier = Modifier.size(Spacing.touchTargetMin)
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                    tint = Color.White
                )
            }
        },
        actions = {
            // Members button (48dp touch target)
            IconButton(
                onClick = onMembersClick,
                modifier = Modifier.size(Spacing.touchTargetMin)
            ) {
                Icon(
                    Icons.Default.People,
                    contentDescription = "Members",
                    tint = Color.White
                )
            }

            if (!isJoined) {
                // Join button for non-members
                TextButton(
                    onClick = onJoinClick,
                    modifier = Modifier.height(Spacing.touchTargetMin),
                    contentPadding = PaddingValues(horizontal = Spacing.md)
                ) {
                    Text(
                        "Join",
                        style = NostrordTypography.Button,
                        color = NostrordColors.Primary
                    )
                }
            } else {
                // Dropdown menu for members
                var menuExpanded by remember { mutableStateOf(false) }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(Spacing.touchTargetMin)
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = Color.White
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = NostrordColors.Surface
                    ) {
                        DropdownMenuItem(
                            text = { Text("Settings", color = NostrordColors.TextPrimary) },
                            onClick = {
                                menuExpanded = false
                                // TODO: Navigate to group settings
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = NostrordColors.TextSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Leave Group",
                                    color = NostrordColors.Error
                                )
                            },
                            onClick = {
                                menuExpanded = false
                                onLeaveClick()
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Filled.ExitToApp,
                                    contentDescription = null,
                                    tint = NostrordColors.Error,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        )
                    }
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = NostrordColors.BackgroundDark
        )
    )
}
