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
import org.nostr.nostrord.ui.Screen

@Composable
fun HomeScreen(
    relayUrl: String? = null,
    gridState: LazyGridState = rememberLazyGridState(),
    onNavigate: (Screen) -> Unit,
    onCreateGroupClick: () -> Unit = {},
    onOpenDrawer: () -> Unit = {}
) {
    val vm = viewModel { HomeViewModel(AppModule.nostrRepository) }

    val allGroups by vm.groups.collectAsState()
    val groupsByRelay by vm.groupsByRelay.collectAsState()
    val connectionState by vm.connectionState.collectAsState()
    val currentRelayUrl by vm.currentRelayUrl.collectAsState()
    val joinedGroups by vm.joinedGroups.collectAsState()

    // Use groups for the selected relay; fall back to the active relay's groups
    val displayRelayUrl = relayUrl ?: currentRelayUrl
    val groups = remember(displayRelayUrl, groupsByRelay, allGroups) {
        groupsByRelay[displayRelayUrl] ?: if (displayRelayUrl == currentRelayUrl) allGroups else emptyList()
    }

    var searchQuery by remember { mutableStateOf("") }

    val filteredGroups = remember(groups, searchQuery) {
        if (searchQuery.isBlank()) groups
        else groups.filter {
            it.name?.contains(searchQuery, ignoreCase = true) == true ||
                    it.id.contains(searchQuery, ignoreCase = true)
        }
    }

    LaunchedEffect(Unit) {
        vm.connect()
    }

    // Detect screen width
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp
        val isMedium = maxWidth in 600.dp..840.dp

        // Determine loading state
        val isLoading = connectionState is ConnectionManager.ConnectionState.Connecting
        val hasError = connectionState is ConnectionManager.ConnectionState.Error

        if (isCompact) {
            HomeScreenMobile(
                gridState = gridState,
                onNavigate = onNavigate,
                joinedGroups = joinedGroups,
                filteredGroups = filteredGroups,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                currentRelayUrl = displayRelayUrl,
                isLoading = isLoading,
                hasError = hasError,
                onRetry = { vm.connect() },
                onCreateGroupClick = onCreateGroupClick,
                onOpenDrawer = onOpenDrawer
            )
        } else {
            HomeScreenDesktop(
                gridState = gridState,
                onNavigate = onNavigate,
                joinedGroups = joinedGroups,
                groups = groups,
                filteredGroups = filteredGroups,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                currentRelayUrl = displayRelayUrl,
                gridColumns = if (isMedium) 2 else 3,
                isLoading = isLoading,
                hasError = hasError,
                onRetry = { vm.connect() }
            )
        }
    }
}
