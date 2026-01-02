package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.network.managers.ConnectionManager
import org.nostr.nostrord.ui.Screen

@Composable
fun HomeScreen(
    gridState: LazyGridState = rememberLazyGridState(),
    onNavigate: (Screen) -> Unit,
    showServerRail: Boolean = true // When false, server rail is handled by parent shell
) {
    val scope = rememberCoroutineScope()

    val groups by NostrRepository.groups.collectAsState()
    val connectionState by NostrRepository.connectionState.collectAsState()
    val currentRelayUrl by NostrRepository.currentRelayUrl.collectAsState()
    val joinedGroups by NostrRepository.joinedGroups.collectAsState()
    val userMetadata by NostrRepository.userMetadata.collectAsState()
    val unreadCounts by NostrRepository.unreadCounts.collectAsState()

    var searchQuery by remember { mutableStateOf("") }

    val filteredGroups = remember(groups, searchQuery) {
        if (searchQuery.isBlank()) groups
        else groups.filter {
            it.name?.contains(searchQuery, ignoreCase = true) == true ||
                    it.id.contains(searchQuery, ignoreCase = true)
        }
    }

    val connectionStatus = when (connectionState) {
        is ConnectionManager.ConnectionState.Disconnected -> "Disconnected"
        is ConnectionManager.ConnectionState.Connecting -> "Connecting..."
        is ConnectionManager.ConnectionState.Connected -> "Connected"
        is ConnectionManager.ConnectionState.Error ->
            "Error: ${(connectionState as ConnectionManager.ConnectionState.Error).message}"
    }

    val pubKey = NostrRepository.getPublicKey()
    val currentUserMetadata = pubKey?.let { userMetadata[it] }

    LaunchedEffect(Unit) {
        scope.launch {
            NostrRepository.connect()
        }
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
                connectionStatus = connectionStatus,
                pubKey = pubKey,
                joinedGroups = joinedGroups,
                groups = groups,
                filteredGroups = filteredGroups,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                currentRelayUrl = currentRelayUrl,
                isLoading = isLoading,
                hasError = hasError,
                onRetry = { scope.launch { NostrRepository.connect() } },
                userAvatarUrl = currentUserMetadata?.picture,
                userDisplayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
                unreadCounts = unreadCounts,
                onGroupClick = { groupId, groupName ->
                    onNavigate(Screen.Group(groupId, groupName))
                },
                onUserClick = { onNavigate(Screen.Profile) },
                showServerRail = showServerRail
            )
        } else {
            HomeScreenDesktop(
                gridState = gridState,
                onNavigate = onNavigate,
                connectionStatus = connectionStatus,
                pubKey = pubKey,
                joinedGroups = joinedGroups,
                groups = groups,
                filteredGroups = filteredGroups,
                searchQuery = searchQuery,
                onSearchChange = { searchQuery = it },
                currentRelayUrl = currentRelayUrl,
                gridColumns = if (isMedium) 2 else 3,
                isLoading = isLoading,
                hasError = hasError,
                onRetry = { scope.launch { NostrRepository.connect() } }
            )
        }
    }
}
