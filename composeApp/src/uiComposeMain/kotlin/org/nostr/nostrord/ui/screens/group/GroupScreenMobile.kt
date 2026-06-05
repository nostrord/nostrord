package org.nostr.nostrord.ui.screens.group

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.ConnectionStatusBanner
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.components.sidebars.MemberSidebar
import org.nostr.nostrord.ui.screens.group.components.ChatSearchBar
import org.nostr.nostrord.ui.screens.group.components.GroupHeaderIcon
import org.nostr.nostrord.ui.screens.group.components.InviteCodeJoinModal
import org.nostr.nostrord.ui.screens.group.components.MessageInput
import org.nostr.nostrord.ui.screens.group.components.MessagesList
import org.nostr.nostrord.ui.screens.group.components.ShareGroupModal
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.screens.group.model.GroupInfo
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.ChatSearch
import org.nostr.nostrord.utils.rememberClipboardWriter
import kotlin.math.abs

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
    pendingReactions: Set<String> = emptySet(),
    currentUserPubkey: String? = null,
    messageInput: String,
    onSendMessage: (String) -> Unit,
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
    availableGroups: List<GroupInfo> = emptyList(),
    groupMentions: Map<String, GroupInfo> = emptyMap(),
    onGroupMentionsChange: (Map<String, GroupInfo>) -> Unit = {},
    replyingToMessage: NostrMessage? = null,
    onReplyClick: (NostrMessage) -> Unit = {},
    onDeleteMessage: (NostrMessage) -> Unit = {},
    onReactionBadgeClick: (messageId: String, emoji: String) -> Unit = { _, _ -> },
    onCancelReply: () -> Unit = {},
    onReachedBottom: () -> Unit = {},
    onLeftBottom: () -> Unit = {},
    onSeenUpTo: (Long) -> Unit = {},
    unreadFromOthersCount: Int = 0,
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
    onManageRelay: () -> Unit = {},
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
    onFetchTargetById: (String) -> Unit = {},
    onInputOverlayVisibilityChange: (Boolean) -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    var showMemberSheet by remember { mutableStateOf(false) }
    val memberSheetState = rememberModalBottomSheetState()

    // In-chat search state (UI-local). matchIds recomputes only when messages or the query change.
    var searchActive by remember(groupId) { mutableStateOf(false) }
    var searchQuery by remember(groupId) { mutableStateOf("") }
    // The cursor is anchored to a message id, not a position. "Search older messages" prepends older
    // matches to searchMatchIds (oldest-first order), which would shift a positional index onto a
    // different message; tracking by id keeps the selection on the row the user picked.
    var anchoredHitId by remember(groupId) { mutableStateOf<String?>(null) }
    // Bumped on every prev/next press so re-pressing scrolls back to the current match even when the
    // anchored id does not change (e.g. a single match, or re-centering after scrolling away).
    var searchScrollNonce by remember(groupId) { mutableStateOf(0) }
    var searchingOlder by remember(groupId) { mutableStateOf(false) }
    // Match count captured when a "search older" dig starts, so the loop can tell when a new older
    // match has appeared and stop there.
    var searchOlderBaseline by remember(groupId) { mutableStateOf(0) }
    // Pages loaded in the current dig. Bounded so one click never drains all history (which would
    // flip hasMore to false and hide the affordance for good); the user can just click again.
    var searchOlderPages by remember(groupId) { mutableStateOf(0) }
    // searchTextById enriches each message with what the user SEES: mentions resolved to @names
    // and nevent/note quotes resolved to the referenced note's text (in-memory only). It is the
    // expensive step, so it recomputes only on messages / metadata / cache changes while search
    // is open; the cheap per-keystroke match below reuses it via the extractor.
    val cachedEventsForSearch by AppModule.nostrRepository.cachedEvents.collectAsState()
    val searchTextById = remember(messages, userMetadata, cachedEventsForSearch, searchActive) {
        if (!searchActive) {
            emptyMap()
        } else {
            ChatSearch.buildSearchTextById(
                messages,
                resolveMention = { pubkey -> userMetadata[pubkey]?.let { it.displayName ?: it.name }?.takeIf(String::isNotBlank) },
                resolveCachedQuote = { id -> cachedEventsForSearch[id]?.content },
            )
        }
    }
    val searchMatchIds = remember(searchTextById, searchQuery) {
        ChatSearch.matchingIds(messages, searchQuery) { searchTextById[it.id] ?: it.content }
    }
    // Cursor: anchored hit position, current id, and inverted 1-based display number (1 = newest).
    val searchCursor = ChatSearch.cursor(searchMatchIds, anchoredHitId)
    val clampedIndex = searchCursor.index
    val currentSearchHitId = searchCursor.currentId
    val searchHitIdSet = remember(searchMatchIds) { searchMatchIds.toSet() }
    // Lock the anchor onto the resolved hit (the first match for a new query) so later list changes
    // keep the cursor by identity. No-op once they agree, so this can't loop.
    LaunchedEffect(currentSearchHitId) {
        if (currentSearchHitId != null && currentSearchHitId != anchoredHitId) anchoredHitId = currentSearchHitId
    }
    val closeSearch = {
        searchActive = false
        searchQuery = ""
        anchoredHitId = null
        searchingOlder = false
    }
    // Prefetch quoted events referenced in loaded messages so their text is searchable even for
    // messages not yet scrolled into view (a quote is otherwise cached only once its message renders;
    // web matched the welcome-inside-a-nevent only because that message happened to be on screen).
    LaunchedEffect(searchActive, messages) {
        if (!searchActive) return@LaunchedEffect
        val cached = AppModule.nostrRepository.cachedEvents.value
        for (ref in ChatSearch.quotedEventRefs(messages)) {
            if (ref.eventId !in cached) AppModule.nostrRepository.requestEventById(ref.eventId, ref.relays, ref.author)
        }
    }
    // On-demand "search older messages": page back through history ONLY until the next older match
    // appears, then stop and jump to it (older matches prepend at the front of searchMatchIds, so a
    // count increase means one was found). No viewport pinning while it digs: the list's own
    // pagination scroll-restore (MessagesList) holds position on prepend, so unlike web we must NOT
    // force a scroll each page (that fights the restore and makes the feed bounce).
    LaunchedEffect(searchingOlder, hasMoreMessages, isLoadingMore, searchCursor.index) {
        if (!searchingOlder) return@LaunchedEffect
        when {
            searchCursor.index > searchOlderBaseline -> {
                // The newest of the newly-found older matches sits just before the anchor's old slot
                // (detection by anchor index, so a new live match appended at the tail can't end it).
                anchoredHitId = searchMatchIds[searchCursor.index - searchOlderBaseline - 1]
                searchScrollNonce++
                searchingOlder = false
            }
            !hasMoreMessages -> searchingOlder = false
            searchOlderPages >= ChatSearch.MAX_SEARCH_OLDER_PAGES -> searchingOlder = false
            !isLoadingMore -> {
                // Pace the dig so the quote prefetch can resolve nevent content of the page just
                // loaded before the next page loads; otherwise a fast relay exhausts all history
                // (hasMore -> false, hiding the affordance) before an older match inside a quote is
                // even detected. If a match appears during the wait, this effect restarts and stops.
                kotlinx.coroutines.delay(300)
                searchOlderPages++
                onLoadMore()
            }
        }
    }
    // Watchdog: never leave the dig (and its spinner) stuck if pagination stops progressing — stop
    // it after a cap so the affordance comes back. Cancelled the moment the dig ends on its own.
    LaunchedEffect(searchingOlder) {
        if (!searchingOlder) return@LaunchedEffect
        kotlinx.coroutines.delay(10_000)
        searchingOlder = false
    }

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
                    onSearchClick = { if (searchActive) closeSearch() else searchActive = true },
                    isClosed = isClosed,
                    initialInviteCode = initialInviteCode,
                )
            },
            containerColor = NostrordColors.Background,
        ) { paddingValues ->
            Box(
                modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .pointerInput(Unit) {
                        // Right-edge leftward swipe opens the member sheet. Custom gesture
                        // (not detectHorizontalDragGestures) so it only claims/consumes drags
                        // that start at the right edge — left-edge drags stay free for the
                        // nav-drawer swipe handled in App.kt (issue #77). Kept as a Box modifier,
                        // not an overlay, so it never blocks the scroll-to-bottom button.
                        val rightZonePx = size.width * 0.15f
                        awaitPointerEventScope {
                            while (true) {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                if (down.position.x < size.width - rightZonePx) continue
                                var totalX = 0f
                                var totalY = 0f
                                var triggered = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    if (triggered) {
                                        change.consume()
                                    } else {
                                        totalX += change.positionChange().x
                                        totalY += change.positionChange().y
                                        if (totalX < -80f && -totalX > abs(totalY)) {
                                            triggered = true
                                            change.consume()
                                            showMemberSheet = true
                                        }
                                    }
                                    if (!change.pressed) break
                                }
                            }
                        }
                    },
            ) {
                // The search bar renders as an OVERLAY inside MessagesList (over the top of the
                // scroller), not in-flow here, so toggling search never resizes the message list and
                // the first scroll-to-match doesn't race a relayout (web parity).
                val searchBar: @Composable () -> Unit = {
                    ChatSearchBar(
                        query = searchQuery,
                        onQueryChange = {
                            searchQuery = it
                            anchoredHitId = null
                            searchingOlder = false
                        },
                        matchCount = searchMatchIds.size,
                        currentPosition = searchCursor.position,
                        onPrev = {
                            if (searchMatchIds.isNotEmpty()) {
                                anchoredHitId = searchMatchIds[ChatSearch.step(clampedIndex, searchMatchIds.size, -1)]
                                searchScrollNonce++
                            }
                        },
                        onNext = {
                            if (searchMatchIds.isNotEmpty()) {
                                anchoredHitId = searchMatchIds[ChatSearch.step(clampedIndex, searchMatchIds.size, +1)]
                                searchScrollNonce++
                            }
                        },
                        onClose = closeSearch,
                        canSearchOlder = searchQuery.trim().length >= 2 && hasMoreMessages && !searchingOlder,
                        isSearchingOlder = searchingOlder,
                        onSearchOlder = {
                            searchOlderBaseline = searchCursor.index
                            searchOlderPages = 0
                            searchingOlder = true
                        },
                        onCancelSearchOlder = { searchingOlder = false },
                    )
                }

                Column(modifier = Modifier.fillMaxSize()) {
                    ConnectionStatusBanner(
                        connectionState = connectionState,
                        onRetry = onReconnect,
                        onManageRelay = onManageRelay,
                    )

                    Box(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    ) {
                        val scope = rememberCoroutineScope()
                        MessagesList(
                            groupId = groupId,
                            chatItems = chatItems,
                            messages = messages,
                            userMetadata = userMetadata,
                            reactions = reactions,
                            pendingReactions = pendingReactions,
                            currentUserPubkey = currentUserPubkey,
                            isJoined = isJoined,
                            isReplying = replyingToMessage != null,
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
                            onLeftBottom = onLeftBottom,
                            onSeenUpTo = onSeenUpTo,
                            unreadFromOthersCount = unreadFromOthersCount,
                            targetMessageId = targetMessageId,
                            onTargetConsumed = onTargetConsumed,
                            onFetchTargetById = onFetchTargetById,
                            swipeToReplyEnabled = true,
                            // Already empty / null when search is inactive (query is "" → no matches),
                            // so no searchActive guard is needed here (parity with web).
                            searchHitIds = searchHitIdSet,
                            currentSearchHitId = currentSearchHitId,
                            searchScrollNonce = searchScrollNonce,
                            searchActive = searchActive,
                            searchBar = searchBar,
                        )
                    }

                    MessageInput(
                        isPendingApproval = isPendingApproval,
                        pendingRequestedAtSeconds = pendingRequestedAtSeconds,
                        onCancelJoinRequest = onCancelJoinRequest,
                        isJoined = isJoined,
                        selectedChannel = selectedChannel,
                        groupId = groupId,
                        groupName = groupName,
                        messageInput = messageInput,
                        onSendMessage = onSendMessage,
                        onJoinGroup = onJoinGroup,
                        groupMembers = groupMembers,
                        mentions = mentions,
                        onMentionsChange = onMentionsChange,
                        availableGroups = availableGroups,
                        groupMentions = groupMentions,
                        onGroupMentionsChange = onGroupMentionsChange,
                        replyingToMessage = replyingToMessage,
                        replyingToMetadata = replyingToMessage?.let { userMetadata[it.pubkey] },
                        userMetadata = userMetadata,
                        onCancelReply = onCancelReply,
                        isSending = isSending,
                        onMediaUploaded = onMediaUploaded,
                        onOverlayVisibilityChange = onInputOverlayVisibilityChange,
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
            sheetMaxWidth = Dp.Unspecified,
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
                modifier = Modifier.fillMaxWidth(),
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
    onSearchClick: () -> Unit = {},
    isClosed: Boolean = false,
    initialInviteCode: String? = null,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(
                onClick = onOpenDrawer,
                modifier = Modifier.size(Spacing.touchTargetMin),
            ) {
                Icon(
                    Icons.Default.Menu,
                    contentDescription = "Open sidebar",
                    tint = Color.White,
                )
            }
        },
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier =
                Modifier
                    .padding(start = Spacing.xs)
                    .clickable(onClick = onTitleClick),
            ) {
                GroupHeaderIcon(
                    pictureUrl = groupMetadata?.picture,
                    groupId = groupMetadata?.id ?: "",
                    displayName = groupName ?: "Group",
                    size = 32.dp,
                )

                Spacer(modifier = Modifier.width(Spacing.sm))

                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = groupMetadata?.name ?: groupName ?: "Unknown Group",
                        style = NostrordTypography.ServerHeader,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    if (!groupMetadata?.about.isNullOrBlank()) {
                        Text(
                            text = groupMetadata?.about ?: "",
                            style = NostrordTypography.Tiny,
                            color = NostrordColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
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
                        modifier = Modifier.size(Spacing.touchTargetMin),
                    ) {
                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = "Join requests",
                            tint = Color.White,
                        )
                    }
                    Box(
                        modifier =
                        Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-4).dp, y = 4.dp)
                            .size(18.dp)
                            .background(NostrordColors.Error, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = if (pendingJoinRequestCount > 9) "9+" else pendingJoinRequestCount.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            IconButton(
                onClick = onSearchClick,
                modifier = Modifier.size(Spacing.touchTargetMin),
            ) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search messages",
                    tint = Color.White,
                )
            }

            IconButton(
                onClick = onMembersClick,
                modifier = Modifier.size(Spacing.touchTargetMin),
            ) {
                Icon(
                    Icons.Default.People,
                    contentDescription = "Members",
                    tint = Color.White,
                )
            }

            if (!isJoined) {
                val showInviteButton = isClosed || initialInviteCode != null
                var showInviteModal by remember { mutableStateOf(initialInviteCode != null) }

                if (showInviteButton) {
                    IconButton(
                        onClick = { showInviteModal = true },
                        modifier = Modifier.size(Spacing.touchTargetMin),
                    ) {
                        Icon(
                            Icons.Default.VpnKey,
                            contentDescription = "Invite Code",
                            tint = Color.White,
                        )
                    }
                }

                TextButton(
                    onClick = { onJoinClick(null) },
                    modifier = Modifier.height(Spacing.touchTargetMin),
                    contentPadding = PaddingValues(horizontal = Spacing.md),
                ) {
                    Text(
                        "Join",
                        style = NostrordTypography.Button,
                        color = NostrordColors.Primary,
                    )
                }

                if (showInviteModal) {
                    InviteCodeJoinModal(
                        initialCode = initialInviteCode ?: "",
                        onJoin = { code ->
                            showInviteModal = false
                            onJoinClick(code)
                        },
                        onDismiss = { showInviteModal = false },
                    )
                }
            } else {
                // Dropdown menu for members
                var menuExpanded by remember { mutableStateOf(false) }
                var showShareModal by remember { mutableStateOf(false) }
                val copyToClipboard = rememberClipboardWriter()

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(Spacing.touchTargetMin),
                    ) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "More options",
                            tint = Color.White,
                        )
                    }

                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                        containerColor = NostrordColors.Surface,
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
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
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
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
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
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
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
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
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
                                            modifier = Modifier.size(20.dp),
                                        )
                                    },
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
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                            )
                        }
                        if (relayUrl.isNotBlank() && groupId.isNotBlank()) {
                            DropdownMenuItem(
                                text = { Text("Share", color = NostrordColors.TextPrimary) },
                                onClick = {
                                    menuExpanded = false
                                    showShareModal = true
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.Share,
                                        contentDescription = null,
                                        tint = NostrordColors.TextSecondary,
                                        modifier = Modifier.size(20.dp),
                                    )
                                },
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Leave Group",
                                    color = NostrordColors.Error,
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
                                    modifier = Modifier.size(20.dp),
                                )
                            },
                        )
                    }
                }

                if (showShareModal && relayUrl.isNotBlank() && groupId.isNotBlank()) {
                    ShareGroupModal(
                        relayUrl = relayUrl,
                        groupId = groupId,
                        onDismiss = { showShareModal = false },
                    )
                }
            }
        },
        colors =
        TopAppBarDefaults.topAppBarColors(
            containerColor = NostrordColors.BackgroundDark,
        ),
    )
}
