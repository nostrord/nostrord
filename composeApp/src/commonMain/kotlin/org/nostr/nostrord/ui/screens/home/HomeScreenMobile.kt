package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.loading.ConnectionErrorState
import org.nostr.nostrord.ui.components.loading.GroupCardSkeleton
import org.nostr.nostrord.ui.components.navigation.ServerRail
import org.nostr.nostrord.ui.screens.home.components.GroupCard
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Mobile home screen - group discovery/explore view.
 *
 * Mobile-first Discord-like pattern:
 * - Server rail on left (when showServerRail=true)
 * - Full-width search
 * - Single-column group cards
 * - Thumb-reachable actions at bottom
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenMobile(
    gridState: LazyGridState,
    onNavigate: (Screen) -> Unit,
    joinedGroups: Set<String>,
    groups: List<GroupMetadata>,
    filteredGroups: List<GroupMetadata>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    currentRelayUrl: String,
    isLoading: Boolean = false,
    hasError: Boolean = false,
    onRetry: () -> Unit = {},
    userAvatarUrl: String? = null,
    userDisplayName: String? = null,
    userPubkey: String? = null,
    unreadCounts: Map<String, Int> = emptyMap(),
    onGroupClick: (groupId: String, groupName: String?) -> Unit = { _, _ -> },
    onUserClick: () -> Unit = {},
    showServerRail: Boolean = true // When false, server rail is handled by parent shell
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Explore Groups",
                        style = NostrordTypography.ServerHeader,
                        color = Color.White
                    )
                },
                actions = {
                    IconButton(
                        onClick = { onNavigate(Screen.RelaySettings) },
                        modifier = Modifier.size(Spacing.touchTargetMin)
                    ) {
                        Icon(
                            Icons.Default.Cloud,
                            contentDescription = "Relay Settings",
                            tint = NostrordColors.TextSecondary
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NostrordColors.BackgroundDark
                )
            )
        },
        containerColor = NostrordColors.Background
    ) { paddingValues ->
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Server rail on the left - only show when not handled by parent shell
            if (showServerRail) {
                ServerRail(
                    joinedGroups = joinedGroups,
                    groups = groups,
                    activeGroupId = null, // No active group on home screen
                    unreadCounts = unreadCounts,
                    onHomeClick = { /* Already on home */ },
                    onGroupClick = onGroupClick,
                    onAddClick = { /* Scroll to explore or show join dialog */ },
                    userAvatarUrl = userAvatarUrl,
                    userDisplayName = userDisplayName,
                    userPubkey = userPubkey,
                    onUserClick = onUserClick
                )
            }

            // Main content area
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NostrordColors.Background)
            ) {
                // Search and header section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(NostrordColors.Background)
                        .padding(horizontal = Spacing.lg, vertical = Spacing.md)
                ) {
                    Text(
                        text = "Discover",
                        style = NostrordTypography.ServerHeader,
                        color = Color.White
                    )

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    Text(
                        text = "${filteredGroups.size} groups on $currentRelayUrl",
                        style = NostrordTypography.Caption,
                        color = NostrordColors.TextSecondary
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // Search input
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        placeholder = {
                            Text(
                                "Search groups...",
                                style = NostrordTypography.InputPlaceholder,
                                color = NostrordColors.TextMuted
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = NostrordTypography.Input.copy(color = Color.White),
                        shape = NostrordShapes.inputShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = NostrordColors.Primary,
                            unfocusedBorderColor = NostrordColors.Divider,
                            focusedContainerColor = NostrordColors.InputBackground,
                            unfocusedContainerColor = NostrordColors.InputBackground
                        )
                    )
                }

                // Grid content
                when {
                    hasError -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            ConnectionErrorState(onRetry = onRetry)
                        }
                    }
                    isLoading && filteredGroups.isEmpty() -> {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(1),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = Spacing.lg,
                                vertical = Spacing.sm
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            items(4) {
                                GroupCardSkeleton()
                            }
                        }
                    }
                    filteredGroups.isEmpty() -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "No groups found",
                                style = NostrordTypography.MessageBody,
                                color = NostrordColors.TextSecondary
                            )
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(1),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                horizontal = Spacing.lg,
                                vertical = Spacing.sm
                            ),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                        ) {
                            items(filteredGroups) { group ->
                                GroupCard(
                                    group = group,
                                    onClick = {
                                        onNavigate(Screen.Group(group.id, group.name))
                                    },
                                    isJoined = joinedGroups.contains(group.id)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
