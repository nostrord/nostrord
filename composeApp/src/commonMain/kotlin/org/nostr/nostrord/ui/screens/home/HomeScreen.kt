package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.utils.normalizeRelayUrl

@Composable
fun HomeScreen(
    relayUrl: String? = null,
    gridState: LazyGridState = rememberLazyGridState(),
    onNavigate: (Screen) -> Unit,
    onCreateGroupClick: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    forceDesktop: Boolean = false,
    initiallyManaging: Boolean = false,
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
    val groups =
        remember(displayRelayUrl, groupsByRelay) {
            groupsByRelay[displayRelayUrl] ?: emptyList()
        }

    // Use relay-specific joined groups so the filter matches the sidebar for the same relay.
    // Normalize the lookup key — joinedGroupsByRelay keys are always normalized but
    // displayRelayUrl may arrive un-normalized from a deep link or navigation argument.
    val joinedGroupIds =
        remember(displayRelayUrl, joinedGroupsByRelay) {
            val key = displayRelayUrl.normalizeRelayUrl()
            joinedGroupsByRelay[key] ?: emptySet()
        }

    val offlineGroups =
        remember(joinedGroupIds, groups) {
            joinedGroupIds.map { id ->
                groups.find { it.id == id } ?: org.nostr.nostrord.network.GroupMetadata(
                    id = id,
                    name = null,
                    about = null,
                    picture = null,
                    isPublic = false,
                    isOpen = false,
                )
            }
        }

    var searchQuery by remember(displayRelayUrl) { mutableStateOf("") }
    var activeFilter by remember(displayRelayUrl) { mutableStateOf(GroupFilter.All) }
    var isManagingRelay by remember(displayRelayUrl) { mutableStateOf(false) }

    val orphanedIds =
        remember(displayRelayUrl, orphanedJoinedByRelay) {
            orphanedJoinedByRelay[displayRelayUrl] ?: emptySet()
        }

    val filteredGroups =
        remember(groups, searchQuery, activeFilter, joinedGroupIds, orphanedIds) {
            val base =
                groups
                    .filter { group ->
                        when (activeFilter) {
                            GroupFilter.All -> true
                            GroupFilter.Joined -> group.id in joinedGroupIds
                        }
                    }.filter { group ->
                        if (searchQuery.isBlank()) {
                            true
                        } else {
                            group.name?.contains(searchQuery, ignoreCase = true) == true ||
                                group.id.contains(searchQuery, ignoreCase = true)
                        }
                    }
            // In the Joined tab, surface orphan pins (kind:10009 ids without a
            // kind:39000) as stub cards so the user can see the count matches
            // reality. Sidebar ghost rows let them forget.
            if (activeFilter == GroupFilter.Joined && orphanedIds.isNotEmpty()) {
                val knownIds = base.map { it.id }.toSet()
                val stubs =
                    orphanedIds
                        .filter { it !in knownIds }
                        .filter { searchQuery.isBlank() || it.contains(searchQuery, ignoreCase = true) }
                        .map { id ->
                            org.nostr.nostrord.network.GroupMetadata(
                                id = id,
                                name = null,
                                about = "Deleted or unavailable on this relay",
                                picture = null,
                                isPublic = false,
                                isOpen = false,
                            )
                        }
                base + stubs
            } else {
                base
            }
        }

    LaunchedEffect(Unit) {
        vm.connect()
        if (initiallyManaging) isManagingRelay = true
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = !forceDesktop

        val connectionState by vm.connectionState.collectAsState()
        val restrictionMessage = restrictedRelays[displayRelayUrl]
        // hasError covers relay-level restrictions only; generic failures show via ConnectionStatusBanner
        val hasError = restrictionMessage != null
        val isLoading =
            displayRelayUrl in loadingRelays ||
                displayRelayUrl.isBlank() ||
                connectionState !is ConnectionManager.ConnectionState.Connected
        val isRelaySaved =
            remember(displayRelayUrl, kind10009Relays) {
                displayRelayUrl.isNotBlank() && displayRelayUrl in kind10009Relays
            }
        val isReachabilityError =
            connectionState is ConnectionManager.ConnectionState.Error ||
                connectionState is ConnectionManager.ConnectionState.Reconnecting

        val onRemoveRelayConfirmed: () -> Unit = {
            vm.removeRelay(displayRelayUrl)
            onNavigate(Screen.Home)
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
                isReachabilityError = isReachabilityError,
                connectionState = connectionState,
                hasError = hasError,
                errorMessage = restrictionMessage,
                onRetry = { vm.connect() },
                onCreateGroupClick = onCreateGroupClick,
                onOpenDrawer = onOpenDrawer,
                onRemoveRelay = { isManagingRelay = true },
                onRemoveRelayConfirmed = onRemoveRelayConfirmed,
                onDismissManagement = { isManagingRelay = false },
                onAddRelay = { vm.addRelay(displayRelayUrl) },
                isRelaySaved = isRelaySaved,
                isOffline = isManagingRelay,
                isActuallyOffline = isReachabilityError,
                offlineGroups = offlineGroups,
                onForgetGroup = { vm.forgetGroup(it, displayRelayUrl) },
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
                isReachabilityError = isReachabilityError,
                connectionState = connectionState,
                hasError = hasError,
                errorMessage = restrictionMessage,
                onRetry = { vm.connect() },
                onRemoveRelay = { isManagingRelay = true },
                onRemoveRelayConfirmed = onRemoveRelayConfirmed,
                onDismissManagement = { isManagingRelay = false },
                onAddRelay = { vm.addRelay(displayRelayUrl) },
                isRelaySaved = isRelaySaved,
                isOffline = isManagingRelay,
                isActuallyOffline = isReachabilityError,
                offlineGroups = offlineGroups,
                onForgetGroup = { vm.forgetGroup(it, displayRelayUrl) },
            )
        }
    }
}
