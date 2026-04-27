package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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

    val groupsByRelay by vm.groupsByRelay.collectAsState()
    val currentRelayUrl by vm.currentRelayUrl.collectAsState()
    val joinedGroupsByRelay by vm.joinedGroupsByRelay.collectAsState()
    val orphanedJoinedByRelay by AppModule.nostrRepository.orphanedJoinedByRelay.collectAsState()
    val relayMetadata by vm.relayMetadata.collectAsState()
    val loadingRelays by vm.loadingRelays.collectAsState()
    val pendingDeepLinkRelay by vm.pendingDeepLinkRelay.collectAsState()
    val kind10009Relays by vm.kind10009Relays.collectAsState()
    val restrictedRelays by vm.restrictedRelays.collectAsState()

    val displayRelayUrl = relayUrl ?: currentRelayUrl
    val relayMeta: Nip11RelayInfo? = relayMetadata[displayRelayUrl]
    val groups = remember(displayRelayUrl, groupsByRelay) {
        groupsByRelay[displayRelayUrl] ?: emptyList()
    }

    // Use relay-specific joined groups so the filter matches the sidebar for the same relay
    val joinedGroupIds = remember(displayRelayUrl, joinedGroupsByRelay) {
        joinedGroupsByRelay[displayRelayUrl] ?: emptySet()
    }

    var searchQuery by remember(displayRelayUrl) { mutableStateOf("") }
    var activeFilter by remember(displayRelayUrl) { mutableStateOf(GroupFilter.All) }

    val orphanedIds = remember(displayRelayUrl, orphanedJoinedByRelay) {
        orphanedJoinedByRelay[displayRelayUrl] ?: emptySet()
    }

    val filteredGroups = remember(groups, searchQuery, activeFilter, joinedGroupIds, orphanedIds) {
        val base = groups
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
        // In the Joined tab, surface orphan pins (kind:10009 ids without a
        // kind:39000) as stub cards so the user can see the count matches
        // reality. Sidebar ghost rows let them forget.
        if (activeFilter == GroupFilter.Joined && orphanedIds.isNotEmpty()) {
            val knownIds = base.map { it.id }.toSet()
            val stubs = orphanedIds
                .filter { it !in knownIds }
                .filter { searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true) }
                .map { id ->
                    org.nostr.nostrord.network.GroupMetadata(
                        id = id,
                        name = null,
                        about = "Deleted or unavailable on this relay",
                        picture = null,
                        isPublic = false,
                        isOpen = false
                    )
                }
            base + stubs
        } else base
    }

    LaunchedEffect(Unit) {
        vm.connect()
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = !forceDesktop
        val gridColumns = when {
            maxWidth < 840.dp -> 2
            else -> 3
        }

        val isLoading = displayRelayUrl in loadingRelays || displayRelayUrl.isBlank()
        val connectionState by vm.connectionState.collectAsState()
        val restrictionMessage = restrictedRelays[displayRelayUrl]
        val hasError = restrictionMessage != null || connectionState is ConnectionManager.ConnectionState.Error
        val errorMessage = restrictionMessage ?: (connectionState as? ConnectionManager.ConnectionState.Error)?.message

        // Check if current relay is in the user's kind:10009 event.
        val isRelaySaved = remember(displayRelayUrl, kind10009Relays) {
            displayRelayUrl.isNotBlank() && displayRelayUrl in kind10009Relays
        }

        if (isCompact) {
            HomeScreenMobile(
                gridState = gridState,
                onNavigate = onNavigate,
                joinedGroups = joinedGroupIds,
                filteredGroups = filteredGroups,
                groupCount = groups.size,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                activeFilter = activeFilter,
                onFilterChange = { activeFilter = it },
                currentRelayUrl = displayRelayUrl,
                relayMeta = relayMeta,
                isLoading = isLoading,
                hasError = hasError,
                errorMessage = errorMessage,
                onRetry = { vm.connect() },
                onCreateGroupClick = onCreateGroupClick,
                onOpenDrawer = onOpenDrawer,
                onRemoveRelay = {
                    vm.removeRelay(displayRelayUrl)
                    onNavigate(Screen.Home)
                },
                onAddRelay = { vm.addRelay(displayRelayUrl) },
                isRelaySaved = isRelaySaved
            )
        } else {
            HomeScreenDesktop(
                gridState = gridState,
                onNavigate = onNavigate,
                joinedGroups = joinedGroupIds,
                groups = groups,
                filteredGroups = filteredGroups,
                groupCount = groups.size,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                activeFilter = activeFilter,
                onFilterChange = { activeFilter = it },
                currentRelayUrl = displayRelayUrl,
                relayMeta = relayMeta,
                isLoading = isLoading,
                hasError = hasError,
                errorMessage = errorMessage,
                onRetry = { vm.connect() },
                onRemoveRelay = {
                    vm.removeRelay(displayRelayUrl)
                    onNavigate(Screen.Home)
                },
                onAddRelay = { vm.addRelay(displayRelayUrl) },
                isRelaySaved = isRelaySaved
            )
        }
    }
}
