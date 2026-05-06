package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.ConnectionStatusBanner
import org.nostr.nostrord.ui.screens.group.components.GroupHeaderIcon
import org.nostr.nostrord.ui.screens.group.components.InviteCodeJoinModal
import org.nostr.nostrord.ui.components.sidebars.MemberSidebar
import org.nostr.nostrord.ui.screens.group.components.MessageInput
import org.nostr.nostrord.ui.screens.group.components.MessagesList
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.buildShareGroupLink
import org.nostr.nostrord.utils.rememberClipboardWriter
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
    relayUrl: String = "",
    groupMetadata: GroupMetadata?,
    selectedChannel: String,
    onChannelSelect: (String) -> Unit,
    onOpenDrawer: () -> Unit = {},
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
    onCreateSubgroup: () -> Unit = {},
    onManageChildren: () -> Unit = {},
    showSubgroupControls: Boolean = true,
    parentGroupName: String? = null,
    onParentClick: () -> Unit = {},
    subgroupCount: Int = 0,
    groupMembers: List<MemberInfo> = emptyList(),
    recentlyActiveMembers: Set<String> = emptySet(),
    mentions: Map<String, String> = emptyMap(),
    onMentionsChange: (Map<String, String>) -> Unit = {},
    replyingToMessage: NostrMessage? = null,
    onReplyClick: (NostrMessage) -> Unit = {},
    onDeleteMessage: (NostrMessage) -> Unit = {},
    onReactionBadgeClick: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onCancelReply: () -> Unit = {},
    onReachedBottom: () -> Unit = {},
    isMembersLoading: Boolean = false,
    isInitialLoading: Boolean = false,
    isLoadingMore: Boolean = false,
    hasMoreMessages: Boolean = true,
    onLoadMore: () -> Unit = {},
    joinedGroups: Set<String> = emptySet(),
    groups: List<GroupMetadata> = emptyList(),
    onNavigateToGroup: (groupId: String, groupName: String?, relayUrl: String?) -> Unit = { _, _, _ -> },
    onUserClick: (String) -> Unit = {},
    onReconnect: () -> Unit = {},
    isSending: Boolean = false,
    onMediaUploaded: (org.nostr.nostrord.network.upload.UploadResult) -> Unit = {},
    isCurrentUserAdmin: Boolean = false,
    onRemoveMember: (MemberInfo) -> Unit = {},
    onAddMember: (String) -> Unit = {},
    pendingJoinRequestCount: Int = 0,
    onJoinRequestsClick: () -> Unit = {},
    isPendingApproval: Boolean = false,
    pendingRequestedAtSeconds: Long? = null,
    onCancelJoinRequest: () -> Unit = {},
    onInviteCodesClick: () -> Unit = {},
    isClosed: Boolean = false,
    isGroupRestricted: Boolean = false,
    initialInviteCode: String? = null,
    targetMessageId: String? = null,
    onTargetConsumed: () -> Unit = {},
    onFetchTargetById: (String) -> Unit = {}
) {
    val scope = rememberCoroutineScope()
    var showMemberSheet by remember { mutableStateOf(false) }
    val memberSheetState = rememberModalBottomSheetState()

    var dragStartX by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val parentHidden = LocalAnimatedImageHidden.current
    CompositionLocalProvider(LocalAnimatedImageHidden provides (parentHidden || showMemberSheet)) {
    Scaffold(
        topBar = {
            MobileGroupTopBar(
                groupName = if (isGroupRestricted && groupName == null) "Private Group" else groupName,
                groupMetadata = groupMetadata,
                relayUrl = relayUrl,
                groupId = groupId,
                isJoined = isJoined,
                isAdmin = isAdmin,
                onOpenDrawer = onOpenDrawer,
                onTitleClick = onShowGroupInfo,
                onMembersClick = { showMemberSheet = true },
                onJoinClick = onJoinGroup,
                onLeaveClick = onLeaveGroup,
                onEditClick = onEditGroup,
                onDeleteClick = onDeleteGroup,
                onManageMembersClick = onManageMembers,
                onCreateSubgroupClick = onCreateSubgroup,
                onManageChildrenClick = onManageChildren,
                showSubgroupControls = showSubgroupControls,
                pendingJoinRequestCount = pendingJoinRequestCount,
                onJoinRequestsClick = onJoinRequestsClick,
                onInviteCodesClick = onInviteCodesClick,
                isClosed = isClosed,
                initialInviteCode = initialInviteCode
            )
        },
        containerColor = NostrordColors.Background
    ) { paddingValues ->
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
                ConnectionStatusBanner(
                    connectionState = connectionState,
                    onRetry = onReconnect
                )

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    val scope = rememberCoroutineScope()
                    MessagesList(
                        groupId = groupId,
                        chatItems = chatItems,
                        messages = messages,
                        userMetadata = userMetadata,
                        reactions = reactions,
                        currentUserPubkey = currentUserPubkey,
                        isJoined = isJoined,
                        isInitialLoading = isInitialLoading,
                        isPendingApproval = isPendingApproval,
                        isGroupRestricted = isGroupRestricted,
                        isLoadingMore = isLoadingMore,
                        hasMoreMessages = hasMoreMessages,
                        onLoadMore = onLoadMore,
                        onUsernameClick = onUserClick,
                        onReplyClick = onReplyClick,
                        onDeleteMessage = onDeleteMessage,
                        onReactionBadgeClick = onReactionBadgeClick,
                        onNavigateToGroup = onNavigateToGroup,
                        onReachedBottom = onReachedBottom,
                        targetMessageId = targetMessageId,
                        onTargetConsumed = onTargetConsumed,
                        onFetchTargetById = onFetchTargetById
                    )
                }

                MessageInput(
                    isPendingApproval = isPendingApproval,
                    pendingRequestedAtSeconds = pendingRequestedAtSeconds,
                    onCancelJoinRequest = onCancelJoinRequest,
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
        }
    }
    } // CompositionLocalProvider

    if (showMemberSheet) {
        ModalBottomSheet(
            onDismissRequest = { showMemberSheet = false },
            sheetState = memberSheetState,
            containerColor = NostrordColors.Surface,
            shape = NostrordShapes.bottomSheetShape,
            sheetMaxWidth = Dp.Unspecified
        ) {
            MemberSidebar(
                members = groupMembers,
                recentlyActiveMembers = recentlyActiveMembers,
                isLoading = isMembersLoading,
                isPendingApproval = isPendingApproval,
                isGroupRestricted = isGroupRestricted,
                onMemberClick = { member ->
                    showMemberSheet = false
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

/** Mobile top bar with avatar, group name, and join/admin actions. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MobileGroupTopBar(
    groupName: String?,
    groupMetadata: GroupMetadata?,
    relayUrl: String = "",
    groupId: String = "",
    isJoined: Boolean,
    isAdmin: Boolean = false,
    onOpenDrawer: () -> Unit = {},
    onTitleClick: () -> Unit,
    onMembersClick: () -> Unit,
    onJoinClick: (inviteCode: String?) -> Unit,
    onLeaveClick: () -> Unit,
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onManageMembersClick: () -> Unit = {},
    onCreateSubgroupClick: () -> Unit = {},
    onManageChildrenClick: () -> Unit = {},
    showSubgroupControls: Boolean = true,
    pendingJoinRequestCount: Int = 0,
    onJoinRequestsClick: () -> Unit = {},
    onInviteCodesClick: () -> Unit = {},
    isClosed: Boolean = false,
    initialInviteCode: String? = null
) {
    TopAppBar(
        navigationIcon = {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.size(Spacing.touchTargetMin)
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Open sidebar",
                    tint = Color.White
                )
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(start = Spacing.xs)
                    .clickable(onClick = onTitleClick)
            ) {
                GroupHeaderIcon(
                    pictureUrl = groupMetadata?.picture,
                    groupId = groupMetadata?.id ?: "",
                    displayName = groupName ?: "Group",
                    size = 32.dp
                )

                Spacer(modifier = Modifier.width(Spacing.sm))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = groupMetadata?.name ?: groupName ?: "Unknown Group",
                        style = NostrordTypography.ServerHeader,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

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
        actions = {
            if (isAdmin && pendingJoinRequestCount > 0) {
                Box {
                    IconButton(
                        onClick = onJoinRequestsClick,
                        modifier = Modifier.size(Spacing.touchTargetMin)
                    ) {
                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = "Join requests",
                            tint = Color.White
                        )
                    }
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-4).dp, y = 4.dp)
                            .size(18.dp)
                            .background(NostrordColors.Error, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (pendingJoinRequestCount > 9) "9+" else pendingJoinRequestCount.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

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
                val showInviteButton = isClosed || initialInviteCode != null
                var showInviteModal by remember { mutableStateOf(initialInviteCode != null) }

                if (showInviteButton) {
                    IconButton(
                        onClick = { showInviteModal = true },
                        modifier = Modifier.size(Spacing.touchTargetMin)
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = "Invite Code",
                            tint = Color.White
                        )
                    }
                }

                TextButton(
                    onClick = { onJoinClick(null) },
                    modifier = Modifier.height(Spacing.touchTargetMin),
                    contentPadding = PaddingValues(horizontal = Spacing.md)
                ) {
                    Text(
                        "Join",
                        style = NostrordTypography.Button,
                        color = NostrordColors.Primary
                    )
                }

                if (showInviteModal) {
                    InviteCodeJoinModal(
                        initialCode = initialInviteCode ?: "",
                        onJoin = { code ->
                            showInviteModal = false
                            onJoinClick(code)
                        },
                        onDismiss = { showInviteModal = false }
                    )
                }
            } else {
                // Dropdown menu for members
                var menuExpanded by remember { mutableStateOf(false) }
                val copyToClipboard = rememberClipboardWriter()

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
                        if (isAdmin) {
                            DropdownMenuItem(
                                text = { Text("Edit Group", color = NostrordColors.TextPrimary) },
                                onClick = {
                                    menuExpanded = false
                                    onEditClick()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Edit,
                                        contentDescription = null,
                                        tint = NostrordColors.TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Manage Members", color = NostrordColors.TextPrimary) },
                                onClick = {
                                    menuExpanded = false
                                    onManageMembersClick()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.People,
                                        contentDescription = null,
                                        tint = NostrordColors.TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                            if (isClosed) {
                                DropdownMenuItem(
                                    text = { Text("Invite Codes", color = NostrordColors.TextPrimary) },
                                    onClick = {
                                        menuExpanded = false
                                        onInviteCodesClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Link,
                                            contentDescription = null,
                                            tint = NostrordColors.TextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                            }
                            if (showSubgroupControls) {
                                DropdownMenuItem(
                                    text = { Text("Create Subgroup", color = NostrordColors.TextPrimary) },
                                    onClick = {
                                        menuExpanded = false
                                        onCreateSubgroupClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = null,
                                            tint = NostrordColors.TextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Manage Children", color = NostrordColors.TextPrimary) },
                                    onClick = {
                                        menuExpanded = false
                                        onManageChildrenClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.AccountTree,
                                            contentDescription = null,
                                            tint = NostrordColors.TextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = { Text("Delete Group", color = NostrordColors.Error) },
                                onClick = {
                                    menuExpanded = false
                                    onDeleteClick()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = NostrordColors.Error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                        if (relayUrl.isNotBlank() && groupId.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("Share", color = NostrordColors.TextPrimary) },
                                onClick = {
                                    menuExpanded = false
                                    copyToClipboard(buildShareGroupLink(relayUrl, groupId))
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = null,
                                        tint = NostrordColors.TextSecondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
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
