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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.NostrGroupClient.NostrMessage
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.ConnectionStatusBanner
import org.nostr.nostrord.ui.components.sidebars.MemberSidebar
import org.nostr.nostrord.ui.screens.group.components.ChatSearchBar
import org.nostr.nostrord.ui.screens.group.components.GroupHeader
import org.nostr.nostrord.ui.screens.group.components.MessageInput
import org.nostr.nostrord.ui.screens.group.components.MessagesList
import org.nostr.nostrord.ui.screens.group.model.ChatItem
import org.nostr.nostrord.ui.screens.group.model.GroupInfo
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.ChatSearch

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
    showMemberSidebar: Boolean = true,
    showMemberSheet: Boolean = false,
    onShowMemberSheet: (Boolean) -> Unit = {},
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
    // In-chat search state (UI-local; not in the ViewModel). matchIds recomputes only when the
    // message list or the query changes; the current index walks it with wraparound.
    var searchActive by remember(groupId) { mutableStateOf(false) }
    var searchQuery by remember(groupId) { mutableStateOf("") }
    // The cursor is anchored to a message id, not a position. "Search older messages" prepends older
    // matches to searchMatchIds (oldest-first order), which would shift a positional index onto a
    // different message; tracking by id keeps the selection on the row the user picked.
    var anchoredHitId by remember(groupId) { mutableStateOf<String?>(null) }
    // Bumped on every prev/next press so re-pressing scrolls back to the current match even when the
    // anchored id does not change (e.g. a single match, or re-centering after scrolling away).
    var searchScrollNonce by remember(groupId) { mutableStateOf(0) }
    // True while paginating older history on demand looking for a match (PR 2).
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
    // appears, then stop and jump to it. Detection is by the ANCHOR's index: older matches prepend
    // at the front, pushing the anchor right, so `cursor.index > baseline` means one was found —
    // whereas a new live match appends at the TAIL (growing the count without moving the anchor),
    // which must NOT end the dig. No viewport pinning while it digs: the list's own pagination
    // scroll-restore (MessagesList) holds position on prepend, so unlike web we must NOT force a
    // scroll each page (that fights the restore and makes the feed bounce).
    LaunchedEffect(searchingOlder, hasMoreMessages, isLoadingMore, searchCursor.index) {
        if (!searchingOlder) return@LaunchedEffect
        when {
            searchCursor.index > searchOlderBaseline -> {
                // The newest of the newly-found older matches sits just before the anchor's old slot.
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

    Row(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
            Modifier
                .fillMaxHeight()
                .weight(1f)
                .background(NostrordColors.Background),
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
                onCreateSubgroupClick = onCreateSubgroup,
                onManageChildrenClick = onManageChildren,
                showSubgroupControls = showSubgroupControls,
                parentGroupName = parentGroupName,
                onParentClick = onParentClick,
                childCount = subgroupCount,
                onInviteCodesClick = onInviteCodesClick,
                isClosed = isClosed,
                initialInviteCode = initialInviteCode,
                pendingJoinRequestCount = pendingJoinRequestCount,
                onJoinRequestsClick = onJoinRequestsClick,
                onSearchClick = { if (searchActive) closeSearch() else searchActive = true },
                trailingIcon =
                if (!showMemberSidebar) {
                    {
                        IconButton(
                            onClick = { onShowMemberSheet(true) },
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = "Members",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                } else {
                    null
                },
            )

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
                    // Already empty / null when search is inactive (query is "" → no matches), so no
                    // searchActive guard is needed here (parity with web).
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

        if (showMemberSidebar) {
            MemberSidebar(
                members = groupMembers,
                recentlyActiveMembers = recentlyActiveMembers,
                isLoading = isMembersLoading,
                isPendingApproval = isPendingApproval,
                isGroupRestricted = isGroupRestricted,
                onMemberClick = { member -> onUserClick(member.pubkey) },
                isCurrentUserAdmin = isCurrentUserAdmin,
                currentUserPubkey = currentUserPubkey,
                onRemoveMember = onRemoveMember,
                onAddMember = onAddMember,
            )
        }
    }

    if (showMemberSheet && !showMemberSidebar) {
        @OptIn(ExperimentalMaterial3Api::class)
        ModalBottomSheet(
            onDismissRequest = { onShowMemberSheet(false) },
            sheetState = rememberModalBottomSheetState(),
            containerColor = NostrordColors.Surface,
            sheetMaxWidth = Dp.Unspecified,
        ) {
            MemberSidebar(
                members = groupMembers,
                recentlyActiveMembers = recentlyActiveMembers,
                isLoading = isMembersLoading,
                isPendingApproval = isPendingApproval,
                isGroupRestricted = isGroupRestricted,
                onMemberClick = { member ->
                    onShowMemberSheet(false)
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
