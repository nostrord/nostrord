package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.components.navigation.ServerRail
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Desktop Shell - Persistent navigation wrapper for desktop screens.
 *
 * This component provides the three-column hierarchy:
 *
 * ┌──────┬──────────────────────────────────────────┐
 * │      │                                          │
 * │ Rail │            Content Area                  │
 * │ 72dp │                                          │
 * │      │                                          │
 * └──────┴──────────────────────────────────────────┘
 *
 * The ServerRail is ALWAYS visible on desktop, providing:
 * - Persistent group navigation
 * - Visual anchor for spatial orientation
 * - Quick-switch between groups
 *
 * Content area receives the remaining width and handles:
 * - Channel sidebar (when in group view)
 * - Message area
 * - Member sidebar (when in group view)
 */
@Composable
fun DesktopShell(
    joinedGroups: Set<String>,
    groups: List<GroupMetadata>,
    activeGroupId: String?,
    unreadCounts: Map<String, Int> = emptyMap(),
    onHomeClick: () -> Unit,
    onGroupClick: (groupId: String, groupName: String?) -> Unit,
    onAddClick: () -> Unit,
    modifier: Modifier = Modifier,
    userAvatarUrl: String? = null,
    userDisplayName: String? = null,
    userPubkey: String? = null,
    onUserClick: () -> Unit = {},
    isProfileActive: Boolean = false,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxSize()
            .background(NostrordColors.BackgroundDark)
    ) {
        // Persistent Server Rail
        ServerRail(
            joinedGroups = joinedGroups,
            groups = groups,
            activeGroupId = activeGroupId,
            unreadCounts = unreadCounts,
            onHomeClick = onHomeClick,
            onGroupClick = onGroupClick,
            onAddClick = onAddClick,
            userAvatarUrl = userAvatarUrl,
            userDisplayName = userDisplayName,
            userPubkey = userPubkey,
            onUserClick = onUserClick,
            isProfileActive = isProfileActive
        )

        // Content area - fills remaining space
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .weight(1f)
        ) {
            content()
        }
    }
}

/**
 * Content wrapper for screens displayed within the DesktopShell.
 *
 * Provides consistent structure for:
 * - Group screen (channel sidebar + messages + member sidebar)
 * - Home screen (full-width content area)
 * - Settings screens (centered content)
 */
@Composable
fun ShellContent(
    channelSidebar: (@Composable () -> Unit)? = null,
    memberSidebar: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Row(
        modifier = modifier.fillMaxSize()
    ) {
        // Channel sidebar (optional, 240dp)
        if (channelSidebar != null) {
            Box(
                modifier = Modifier
                    .width(Spacing.channelSidebarWidth)
                    .fillMaxHeight()
            ) {
                channelSidebar()
            }
        }

        // Main content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(NostrordColors.Background)
        ) {
            content()
        }

        // Member sidebar (optional, 240dp)
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
