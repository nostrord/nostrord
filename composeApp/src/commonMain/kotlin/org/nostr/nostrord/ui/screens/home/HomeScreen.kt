package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.ui.Screen

@Composable
fun HomeScreen(
    relayUrl: String? = null,
    gridState: LazyGridState = rememberLazyGridState(),
    onNavigate: (Screen) -> Unit,
    onCreateGroupClick: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    forceDesktop: Boolean = false
) {
    val vm = viewModel { HomeViewModel(AppModule.nostrRepository) }

    val allGroups by vm.groups.collectAsState()
    val groupsByRelay by vm.groupsByRelay.collectAsState()
    val connectionState by vm.connectionState.collectAsState()
    val currentRelayUrl by vm.currentRelayUrl.collectAsState()
    val joinedGroups by vm.joinedGroups.collectAsState()
    val joinedGroupsByRelay by vm.joinedGroupsByRelay.collectAsState()
    val relayMetadata by vm.relayMetadata.collectAsState()

    val displayRelayUrl = relayUrl ?: currentRelayUrl
    val relayMeta: Nip11RelayInfo? = relayMetadata[displayRelayUrl]
    val groups = remember(displayRelayUrl, groupsByRelay, allGroups) {
        groupsByRelay[displayRelayUrl] ?: if (displayRelayUrl == currentRelayUrl) allGroups else emptyList()
    }

    // Use relay-specific joined groups so the filter matches the sidebar for the same relay
    val joinedGroupIds = remember(displayRelayUrl, joinedGroupsByRelay, joinedGroups, currentRelayUrl) {
        joinedGroupsByRelay[displayRelayUrl]
            ?: if (displayRelayUrl == currentRelayUrl) joinedGroups else emptySet()
    }

    var searchQuery by remember(displayRelayUrl) { mutableStateOf("") }
    var activeFilter by remember(displayRelayUrl) { mutableStateOf(GroupFilter.All) }

    val filteredGroups = remember(groups, searchQuery, activeFilter, joinedGroupIds) {
        groups
            .filter { group ->
                when (activeFilter) {
                    GroupFilter.All -> true
                    GroupFilter.Joined -> group.id in joinedGroupIds
                }
            }
            .filter { group ->
                if (searchQuery.isBlank()) true
                else group.name?.contains(searchQuery, ignoreCase = true) == true ||
                     group.id.contains(searchQuery, ignoreCase = true)
            }
    }

    LaunchedEffect(Unit) {
        vm.connect()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = !forceDesktop && maxWidth < 600.dp
        val gridColumns = when {
            maxWidth < 840.dp -> 2
            else -> 3
        }

        val isLoading = connectionState is ConnectionManager.ConnectionState.Connecting
        val hasError = connectionState is ConnectionManager.ConnectionState.Error

        if (isCompact) {
            HomeScreenMobile(
                gridState = gridState,
                onNavigate = onNavigate,
                joinedGroups = joinedGroupIds,
                filteredGroups = filteredGroups,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                activeFilter = activeFilter,
                onFilterChange = { activeFilter = it },
                currentRelayUrl = displayRelayUrl,
                relayMeta = relayMeta,
                isLoading = isLoading,
                hasError = hasError,
                onRetry = { vm.connect() },
                onCreateGroupClick = onCreateGroupClick,
                onOpenDrawer = onOpenDrawer,
                onRemoveRelay = {
                    vm.removeRelay(displayRelayUrl)
                    onNavigate(Screen.Home)
                }
            )
        } else {
            HomeScreenDesktop(
                gridState = gridState,
                onNavigate = onNavigate,
                joinedGroups = joinedGroupIds,
                groups = groups,
                filteredGroups = filteredGroups,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                activeFilter = activeFilter,
                onFilterChange = { activeFilter = it },
                currentRelayUrl = displayRelayUrl,
                relayMeta = relayMeta,
                isLoading = isLoading,
                hasError = hasError,
                onRetry = { vm.connect() },
                onRemoveRelay = {
                    vm.removeRelay(displayRelayUrl)
                    onNavigate(Screen.Home)
                }
            )
        }
    }
}
