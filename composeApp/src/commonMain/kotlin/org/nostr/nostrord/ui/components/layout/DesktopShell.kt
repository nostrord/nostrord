package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.nostr.Nip11RelayInfo
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
 */
@Composable
fun DesktopShell(
    relays: List<String>,
    activeRelayUrl: String,
    groupsForRelay: List<GroupMetadata>,
    joinedGroupIds: Set<String>,
    activeGroupId: String?,
    unreadCounts: Map<String, Int> = emptyMap(),
    relayMetadata: Map<String, Nip11RelayInfo> = emptyMap(),
    onRelayClick: (String) -> Unit,
    onAddRelayClick: () -> Unit,
    onGroupClick: (groupId: String, groupName: String?) -> Unit,
    onCreateGroupClick: () -> Unit,
    modifier: Modifier = Modifier,
    userAvatarUrl: String? = null,
    userDisplayName: String? = null,
    userPubkey: String? = null,
    onUserClick: () -> Unit = {},
    isProfileActive: Boolean = false,
    content: @Composable () -> Unit
) {
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(NostrordColors.BackgroundDark)
    ) {
        // 200dp on medium (600–840dp), 240dp on large (>840dp)
        val sidebarWidth = if (maxWidth < 840.dp) 200.dp else Spacing.channelSidebarWidth

        Row(modifier = Modifier.fillMaxSize()) {
            // Column 1: Relay Rail (72dp)
            ServerRail(
                relays = relays,
                activeRelayUrl = activeRelayUrl,
                onRelayClick = onRelayClick,
                onAddRelayClick = onAddRelayClick,
                relayMetadata = relayMetadata,
                userAvatarUrl = userAvatarUrl,
                userDisplayName = userDisplayName,
                userPubkey = userPubkey,
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
                    onGroupClick = onGroupClick,
                    onCreateGroupClick = onCreateGroupClick
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
