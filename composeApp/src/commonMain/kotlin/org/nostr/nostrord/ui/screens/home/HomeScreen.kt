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
    gridState: LazyGridState = rememberLazyGridState(),
    onNavigate: (Screen) -> Unit,
    showServerRail: Boolean = true, // When false, server rail is handled by parent shell
    onCreateGroupClick: () -> Unit = {}
) {
    val vm = viewModel { HomeViewModel(AppModule.nostrRepository) }

    val groups by vm.groups.collectAsState()
    val connectionState by vm.connectionState.collectAsState()
    val currentRelayUrl by vm.currentRelayUrl.collectAsState()
    val joinedGroups by vm.joinedGroups.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()
    val unreadCounts by vm.unreadCounts.collectAsState()

    var searchQuery by remember { mutableStateOf("") }

    val filteredGroups = remember(groups, searchQuery) {
        if (searchQuery.isBlank()) groups
        else groups.filter {
            it.name?.contains(searchQuery, ignoreCase = true) == true ||
                    it.id.contains(searchQuery, ignoreCase = true)
        }
    }

    val pubKey = vm.getPublicKey()
    val currentUserMetadata = pubKey?.let { userMetadata[it] }

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
                groups = groups,
                filteredGroups = filteredGroups,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                currentRelayUrl = currentRelayUrl,
                isLoading = isLoading,
                hasError = hasError,
                onRetry = { vm.connect() },
                userAvatarUrl = currentUserMetadata?.picture,
                userDisplayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
                userPubkey = pubKey,
                unreadCounts = unreadCounts,
                onGroupClick = { groupId, groupName ->
                    onNavigate(Screen.Group(groupId, groupName))
                },
                onUserClick = { onNavigate(Screen.Profile) },
                showServerRail = showServerRail,
                onCreateGroupClick = onCreateGroupClick
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
                currentRelayUrl = currentRelayUrl,
                gridColumns = if (isMedium) 2 else 3,
                isLoading = isLoading,
                hasError = hasError,
                onRetry = { vm.connect() }
            )
        }
    }
}
