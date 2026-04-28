package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.components.navigation.ServerRail
import org.nostr.nostrord.ui.components.sidebars.GroupsNavSidebar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Desktop Shell — three-column persistent layout.
 *
 * ┌──────────┬───────────────────┬──────────────────────────┐
 * │          │                   │                          │
 * │  Relay   │   Groups Nav      │        Content           │
 * │  Rail    │   Sidebar         │  (chat / home / etc.)    │
 * │  72dp    │   240dp           │        flex              │
 * │          │                   │                          │
 * └──────────┴───────────────────┴──────────────────────────┘
 *
 * Column 1: Relay Rail — one icon per NIP-29 relay. Clicking switches the active relay.
 * Column 2: Groups Nav Sidebar — joined groups for the active relay. Clicking opens the chat.
 * Column 3: Content — the active screen (group chat, home, settings, etc.)
 *
 * Collects its own sidebar state (groups, members, metadata, unread counts) so that
 * sidebar-only updates do NOT recompose the content area or the parent App composable.
 */
@Composable
fun DesktopShell(
    relays: List<String>,
    activeRelayUrl: String,
    activeGroupId: String?,
    isGroupsLoading: Boolean = false,
    onRelayClick: (String) -> Unit,
    onRelayTitleClick: () -> Unit = {},
    onAddRelayClick: () -> Unit,
    onGroupClick: (groupId: String, groupName: String?) -> Unit,
    onCreateGroupClick: () -> Unit,
    onJoinGroupClick: () -> Unit = {},
    onAddRelayFromSidebar: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onUserClick: () -> Unit = {},
    isProfileActive: Boolean = false,
    content: @Composable () -> Unit
) {
    // Sidebar-scoped state — changes here only recompose the sidebar columns,
    // not the content area or the parent AuthenticatedApp.
    val groupsByRelay by AppModule.nostrRepository.groupsByRelay.collectAsState()
    val joinedGroupsByRelay by AppModule.nostrRepository.joinedGroupsByRelay.collectAsState()
    val unreadCounts by AppModule.nostrRepository.unreadCounts.collectAsState()
    val lastMessageAt by AppModule.nostrRepository.latestMessageTimestamps.collectAsState()
    val unreadByRelay by AppModule.nostrRepository.unreadByRelay.collectAsState()
    val childrenByParentRaw by AppModule.nostrRepository.childrenByParent.collectAsState()
    val unverifiedChildrenRaw by AppModule.nostrRepository.unverifiedChildren.collectAsState()
    val subgroupsEnabled by AppModule.featureFlags.subgroupsEnabled.collectAsState()
    // When the experimental subgroups flag is off, pass through empty maps/sets
    // so the sidebar renders a flat list — hides the hierarchy indent and the
    // unverified-claims subheader until the user opts in.
    val childrenByParent = if (subgroupsEnabled) childrenByParentRaw else emptyMap()
    val unverifiedChildren = if (subgroupsEnabled) unverifiedChildrenRaw else emptySet()

    val relayMetadata by AppModule.nostrRepository.relayMetadata.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val orphanedJoinedByRelay by AppModule.nostrRepository.orphanedJoinedByRelay.collectAsState()
    val fullGroupListFetchedRelays by AppModule.nostrRepository.fullGroupListFetchedRelays.collectAsState()
    val connectionState by AppModule.nostrRepository.connectionState.collectAsState()
    val isRelayConnected = connectionState is ConnectionManager.ConnectionState.Connected
    val sidebarScope = rememberCoroutineScope()

    val groupsForRelay = remember(activeRelayUrl, groupsByRelay) {
        groupsByRelay[activeRelayUrl] ?: emptyList()
    }
    val joinedGroupIds = remember(activeRelayUrl, joinedGroupsByRelay) {
        joinedGroupsByRelay[activeRelayUrl] ?: emptySet()
    }
    val orphanedJoinedIds = remember(activeRelayUrl, orphanedJoinedByRelay) {
        orphanedJoinedByRelay[activeRelayUrl] ?: emptySet()
    }


    val pubKey = remember { AppModule.nostrRepository.getPublicKey() }
    val currentUserMetadata = remember(pubKey, userMetadata) {
        pubKey?.let { userMetadata[it] }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(NostrordColors.BackgroundDark)
    ) {
        val sidebarWidth = Spacing.channelSidebarWidth

        Row(modifier = Modifier.fillMaxSize()) {
            // Column 1: Relay Rail (72dp)
            ServerRail(
                relays = relays,
                activeRelayUrl = activeRelayUrl,
                onRelayClick = onRelayClick,
                onAddRelayClick = onAddRelayClick,
                relayMetadata = relayMetadata,
                unreadByRelay = unreadByRelay,
                userAvatarUrl = currentUserMetadata?.picture,
                userDisplayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
                userPubkey = pubKey,
                onUserClick = onUserClick,
                isProfileActive = isProfileActive
            )

            // Column 2: Groups Nav Sidebar (200dp on tablet, 240dp on desktop)
            Box(modifier = Modifier.width(sidebarWidth)) {
                GroupsNavSidebar(
                    relayUrl = activeRelayUrl,
                    groups = groupsForRelay,
                    joinedGroupIds = joinedGroupIds,
                    activeGroupId = activeGroupId,
                    unreadCounts = unreadCounts,
                    lastMessageAt = lastMessageAt,
                    relayName = relayMetadata[activeRelayUrl]?.name,
                    isLoading = isGroupsLoading,
                    childrenByParent = childrenByParent,
                    unverifiedChildren = unverifiedChildren,
                    onGroupClick = onGroupClick,
                    onCreateGroupClick = onCreateGroupClick,
                    onJoinGroupClick = onJoinGroupClick,
                    onAddRelay = onAddRelayFromSidebar,
                    onRelayTitleClick = onRelayTitleClick,
                    orphanedJoinedIds = orphanedJoinedIds,
                    onForgetOrphan = { groupId ->
                        sidebarScope.launch {
                            AppModule.nostrRepository.forgetGroup(groupId, activeRelayUrl)
                        }
                    },
                    isGroupFetchLazy = AppModule.nostrRepository.isGroupFetchLazy(activeRelayUrl),
                    hasFullGroupListBeenFetched = activeRelayUrl in fullGroupListFetchedRelays,
                    onRequestFullGroupList = {
                        sidebarScope.launch {
                            AppModule.nostrRepository.requestFullGroupListForRelay(activeRelayUrl)
                        }
                    },
                    isRelayConnected = isRelayConnected
                )
            }

            // Column 3: Content (flex)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(1f)
            ) {
                content()
            }
        }
    }
}

/**
 * Optional inner layout for screens that need a member sidebar alongside content.
 * Used by GroupScreen to show: Messages | MemberSidebar.
 */
@Composable
fun ShellContent(
    memberSidebar: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(NostrordColors.Background)
        ) {
            content()
        }

        if (memberSidebar != null) {
            Box(
                modifier = Modifier
                    .width(Spacing.memberSidebarWidth)
                    .fillMaxHeight()
            ) {
                memberSidebar()
            }
        }
    }
}
