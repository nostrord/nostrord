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
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.loading.ConnectionErrorState
import org.nostr.nostrord.ui.components.loading.GroupCardSkeleton
import org.nostr.nostrord.ui.screens.home.components.GroupCard
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Desktop home screen - group discovery/explore view.
 *
 * When inside DesktopShell, the ServerRail handles group navigation.
 * This screen focuses on exploring and finding new groups.
 *
 * Layout:
 * ┌─────────────────────────────────────────────┐
 * │ Header: Relay info + Settings               │
 * ├─────────────────────────────────────────────┤
 * │ Search bar                                  │
 * ├─────────────────────────────────────────────┤
 * │                                             │
 * │        Group cards grid (2-3 columns)       │
 * │                                             │
 * └─────────────────────────────────────────────┘
 */
@Composable
fun HomeScreenDesktop(
    gridState: LazyGridState,
    onNavigate: (Screen) -> Unit,
    joinedGroups: Set<String>,
    groups: List<GroupMetadata>,
    filteredGroups: List<GroupMetadata>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    currentRelayUrl: String,
    gridColumns: Int,
    isLoading: Boolean = false,
    hasError: Boolean = false,
    onRetry: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NostrordColors.Background)
    ) {
        // Header bar
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.headerHeight)
                .background(NostrordColors.BackgroundDark)
                .padding(horizontal = Spacing.lg),
            contentAlignment = Alignment.CenterStart
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Explore Groups",
                    style = NostrordTypography.ServerHeader,
                    color = Color.White
                )
                IconButton(onClick = { onNavigate(Screen.RelaySettings) }) {
                    Icon(
                        Icons.Default.Cloud,
                        contentDescription = "Relay Settings",
                        tint = NostrordColors.TextSecondary
                    )
                }
            }
        }

        // Search and content area
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = Spacing.lg)
        ) {
            // Search header
            Spacer(modifier = Modifier.height(Spacing.lg))

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

            Spacer(modifier = Modifier.height(Spacing.lg))

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
                        columns = GridCells.Fixed(gridColumns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(vertical = Spacing.sm),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        items(6) {
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
                    Box(modifier = Modifier.fillMaxSize()) {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(gridColumns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(vertical = Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
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

                        VerticalScrollbarWrapper(
                            gridState = gridState,
                            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                        )
                    }
                }
            }
        }
    }
}
