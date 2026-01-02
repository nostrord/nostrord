package org.nostr.nostrord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.layout.DesktopShell
import org.nostr.nostrord.ui.screens.home.HomeScreen
import org.nostr.nostrord.ui.screens.group.GroupScreen
import org.nostr.nostrord.ui.screens.relay.RelaySettingsScreen
import org.nostr.nostrord.ui.screens.login.NostrLoginScreen
import org.nostr.nostrord.ui.screens.backup.BackupScreen
import org.nostr.nostrord.ui.screens.profile.ProfileScreen
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Main application entry point.
 *
 * Desktop layout uses a persistent ServerRail (72dp) that is always visible,
 * providing Discord-like navigation between groups.
 *
 * Screen size breakpoints:
 * - < 600dp: Mobile (no server rail, bottom navigation)
 * - >= 600dp: Desktop (server rail + sidebars)
 */
@Composable
fun App() {
    val isLoggedIn by NostrRepository.isLoggedIn.collectAsState()
    val isInitialized by NostrRepository.isInitialized.collectAsState()
    var currentScreen by remember { mutableStateOf<Screen>(Screen.Home) }

    // Collect state needed for DesktopShell
    val groups by NostrRepository.groups.collectAsState()
    val joinedGroups by NostrRepository.joinedGroups.collectAsState()
    val unreadCounts by NostrRepository.unreadCounts.collectAsState()
    val userMetadata by NostrRepository.userMetadata.collectAsState()

    // Get pubKey reactively - recalculate when login state changes
    val pubKey = remember(isLoggedIn) { NostrRepository.getPublicKey() }
    val currentUserMetadata = remember(pubKey, userMetadata) {
        pubKey?.let { userMetadata[it] }
    }

    // Remember scroll states across navigation
    val homeGridState = rememberLazyGridState()
    val relayListState = rememberLazyListState()

    // Initialize repository on app start (checks for saved credentials)
    // Add timeout to prevent indefinite loading on mobile browsers
    LaunchedEffect(Unit) {
        withTimeoutOrNull(30000) {
            NostrRepository.initialize()
        } ?: run {
            // Force initialization to complete so the app is usable
            NostrRepository.forceInitialized()
        }
    }

    MaterialTheme {
        // Wait for initialization before deciding which screen to show
        if (!isInitialized) {
            // Show loading screen with app background color
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(NostrordColors.Background),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = NostrordColors.Primary)
            }
            return@MaterialTheme
        }

        if (!isLoggedIn) {
            // Show login screen if not logged in
            NostrLoginScreen {
                // After successful login, stay on home
                currentScreen = Screen.Home
            }
        } else {
            // Determine current active group ID for server rail highlighting
            val activeGroupId = when (val screen = currentScreen) {
                is Screen.Group -> screen.groupId
                else -> null
            }

            BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val isDesktop = maxWidth >= 600.dp

                if (isDesktop) {
                    // Desktop: Wrap content with persistent DesktopShell
                    DesktopShell(
                        joinedGroups = joinedGroups,
                        groups = groups,
                        activeGroupId = activeGroupId,
                        unreadCounts = unreadCounts,
                        onHomeClick = {
                            currentScreen = Screen.Home
                        },
                        onGroupClick = { groupId, groupName ->
                            currentScreen = Screen.Group(groupId, groupName)
                        },
                        onAddClick = {
                            // Navigate to home/explore to find new groups
                            currentScreen = Screen.Home
                        },
                        userAvatarUrl = currentUserMetadata?.picture,
                        userDisplayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
                        userPubkey = pubKey,
                        onUserClick = { currentScreen = Screen.Profile },
                        isProfileActive = currentScreen is Screen.Profile
                    ) {
                        // Content inside the shell (without server rail)
                        DesktopContent(
                            currentScreen = currentScreen,
                            homeGridState = homeGridState,
                            relayListState = relayListState,
                            onNavigate = { newScreen -> currentScreen = newScreen }
                        )
                    }
                } else {
                    // Mobile: Direct rendering without shell
                    MobileContent(
                        currentScreen = currentScreen,
                        homeGridState = homeGridState,
                        relayListState = relayListState,
                        onNavigate = { newScreen -> currentScreen = newScreen }
                    )
                }
            }
        }
    }
}

/**
 * Desktop content - screens rendered inside the DesktopShell.
 * Server rail is handled by the shell, so screens don't include it.
 */
@Composable
private fun DesktopContent(
    currentScreen: Screen,
    homeGridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    relayListState: androidx.compose.foundation.lazy.LazyListState,
    onNavigate: (Screen) -> Unit
) {
    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                gridState = homeGridState,
                onNavigate = onNavigate,
                showServerRail = false // Server rail is in the shell
            )
        }
        is Screen.Group -> {
            GroupScreen(
                groupId = screen.groupId,
                groupName = screen.groupName,
                onBack = { onNavigate(Screen.Home) },
                onNavigateToGroup = { newGroupId, newGroupName ->
                    onNavigate(Screen.Group(newGroupId, newGroupName))
                },
                showServerRail = false // Server rail is in the shell
            )
        }
        is Screen.RelaySettings -> {
            RelaySettingsScreen(
                listState = relayListState,
                onNavigate = onNavigate
            )
        }
        is Screen.Profile -> {
            ProfileScreen(
                onNavigate = onNavigate,
                onLogout = { onNavigate(Screen.Home) }
            )
        }
        is Screen.PAGE1 -> {
            HomeScreen(
                gridState = homeGridState,
                onNavigate = onNavigate,
                showServerRail = false
            )
        }
        is Screen.NostrLogin -> {
            NostrLoginScreen {
                onNavigate(Screen.Home)
            }
        }
        is Screen.BackupPrivateKey -> BackupScreen(
            onNavigateBack = { onNavigate(Screen.Home) }
        )
    }
}

/**
 * Mobile content - screens rendered directly without shell.
 * Mobile screens handle their own navigation (bottom bar, drawer).
 */
@Composable
private fun MobileContent(
    currentScreen: Screen,
    homeGridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    relayListState: androidx.compose.foundation.lazy.LazyListState,
    onNavigate: (Screen) -> Unit
) {
    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                gridState = homeGridState,
                onNavigate = onNavigate
            )
        }
        is Screen.Group -> {
            GroupScreen(
                groupId = screen.groupId,
                groupName = screen.groupName,
                onBack = { onNavigate(Screen.Home) },
                onNavigateToGroup = { newGroupId, newGroupName ->
                    onNavigate(Screen.Group(newGroupId, newGroupName))
                }
            )
        }
        is Screen.RelaySettings -> {
            RelaySettingsScreen(
                listState = relayListState,
                onNavigate = onNavigate
            )
        }
        is Screen.Profile -> {
            ProfileScreen(
                onNavigate = onNavigate,
                onLogout = { onNavigate(Screen.Home) }
            )
        }
        is Screen.PAGE1 -> {
            HomeScreen(
                gridState = homeGridState,
                onNavigate = onNavigate
            )
        }
        is Screen.NostrLogin -> {
            NostrLoginScreen {
                onNavigate(Screen.Home)
            }
        }
        is Screen.BackupPrivateKey -> BackupScreen(
            onNavigateBack = { onNavigate(Screen.Home) }
        )
    }
}
