package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.components.navigation.BottomNavItem
import org.nostr.nostrord.ui.components.navigation.BottomNavigationBar
import org.nostr.nostrord.ui.components.navigation.GroupQuickSwitchBarCompact
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.loading.ConnectionErrorState
import org.nostr.nostrord.ui.components.loading.GroupCardSkeleton
import org.nostr.nostrord.ui.components.sidebars.Sidebar
import org.nostr.nostrord.ui.screens.home.components.GroupCard
import org.nostr.nostrord.ui.theme.NostrordColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenMobile(
    gridState: LazyGridState,
    onNavigate: (Screen) -> Unit,
    connectionStatus: String,
    pubKey: String?,
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
    userDisplayName: String? = null
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = NostrordColors.Surface
            ) {
                Sidebar(
                    onNavigate = { screen ->
                        scope.launch { drawerState.close() }
                        onNavigate(screen)
                    },
                    connectionStatus = connectionStatus,
                    pubKey = pubKey,
                    joinedGroups = joinedGroups,
                    groups = groups
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Nostrord", color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu", tint = Color.White)
                        }
                    },
                    actions = {
                        IconButton(onClick = { onNavigate(Screen.RelaySettings) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = NostrordColors.BackgroundDark
                    )
                )
            },
            bottomBar = {
                BottomNavigationBar(
                    selectedItem = BottomNavItem.Home,
                    onItemSelected = { item ->
                        when (item) {
                            BottomNavItem.Home -> { /* Already on home */ }
                            BottomNavItem.Messages -> {
                                // Navigate to first joined group if available
                                joinedGroups.firstOrNull()?.let { groupId ->
                                    val group = groups.find { it.id == groupId }
                                    onNavigate(Screen.Group(groupId, group?.name))
                                }
                            }
                            BottomNavItem.Notifications -> { /* Future feature */ }
                            BottomNavItem.Profile -> {
                                // Open sidebar for profile
                                scope.launch { drawerState.open() }
                            }
                        }
                    },
                    userAvatarUrl = userAvatarUrl,
                    userDisplayName = userDisplayName,
                    userPubkey = pubKey
                )
            },
            containerColor = NostrordColors.Background
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                // Quick-switch bar for joined groups
                if (joinedGroups.isNotEmpty()) {
                    GroupQuickSwitchBarCompact(
                        joinedGroups = joinedGroups,
                        groups = groups,
                        activeGroupId = null, // No active group on home screen
                        onHomeClick = { /* Already on home */ },
                        onGroupClick = { groupId, groupName ->
                            onNavigate(Screen.Group(groupId, groupName))
                        },
                        onExploreClick = { /* Already on explore/home */ }
                    )
                }

                // Search and header
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Explore", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Groups found: ${filteredGroups.size}", color = NostrordColors.TextSecondary)
                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = onSearchChange,
                        placeholder = { Text("Search groups...", color = NostrordColors.TextSecondary) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = LocalTextStyle.current.copy(color = Color.White),
                        shape = RoundedCornerShape(8.dp)
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
                        // Show skeleton loaders while loading
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(1),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
                            Text("No groups found", color = NostrordColors.TextSecondary)
                        }
                    }
                    else -> {
                        LazyVerticalGrid(
                            state = gridState,
                            columns = GridCells.Fixed(1),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
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
