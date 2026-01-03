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
import org.nostr.nostrord.startup.AppStartState
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.layout.DesktopShell
import org.nostr.nostrord.ui.screens.home.HomeScreen
import org.nostr.nostrord.ui.screens.group.GroupScreen
import org.nostr.nostrord.ui.screens.relay.RelaySettingsScreen
import org.nostr.nostrord.ui.screens.login.NostrLoginScreen
import org.nostr.nostrord.ui.screens.backup.BackupScreen
import org.nostr.nostrord.ui.screens.profile.EditProfileScreen
import org.nostr.nostrord.ui.screens.profile.ProfileScreen
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Main application entry point.
 *
 * ARCHITECTURE:
 * 1. Bootstrap Phase: Initialize repository, resolve startup state
 * 2. Render Phase: Display UI based on resolved state
 *
 * The startup state is computed ONCE before any content UI is rendered.
 * Navigation state is initialized from the resolved startup state.
 * This prevents any screen flicker or navigation corrections.
 *
 * Screen size breakpoints:
 * - < 600dp: Mobile (no server rail, bottom navigation)
 * - >= 600dp: Desktop (server rail + sidebars)
 */
@Composable
fun App() {
    // Collect reactive state from repository
    val isInitialized by NostrRepository.isInitialized.collectAsState()
    val isLoggedIn by NostrRepository.isLoggedIn.collectAsState()

    // Phase 1: Trigger initialization (runs once)
    LaunchedEffect(Unit) {
        withTimeoutOrNull(30000) {
            NostrRepository.initialize()
        } ?: run {
            NostrRepository.forceInitialized()
        }
    }

    // Phase 2: Compute startup state synchronously from current values
    // This is recomputed when dependencies change, but the key insight is
    // that we don't render content UI until state is fully resolved
    val startupState: AppStartState = remember(isInitialized, isLoggedIn) {
        StartupResolver.resolve(isInitialized, isLoggedIn)
    }

    MaterialTheme {
        // Phase 3: Render based on resolved startup state
        when (startupState) {
            is AppStartState.Initializing -> {
                // Bootstrap not complete - show loading
                LoadingScreen()
            }

            is AppStartState.Unauthenticated -> {
                // Not logged in - show login
                NostrLoginScreen {
                    // After login, the startupState will recompute due to isLoggedIn change
                }
            }

            is AppStartState.Authenticated -> {
                // Authenticated with resolved initial screen
                // Now we can create the navigation state with the correct initial value
                AuthenticatedApp(
                    initialScreen = startupState.initialScreen
                )
            }
        }
    }
}

/**
 * Loading screen shown during bootstrap.
 */
@Composable
private fun LoadingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NostrordColors.Background),
        contentAlignment = Alignment.Center
    ) {
        CircularProgressIndicator(color = NostrordColors.Primary)
    }
}

/**
 * Main authenticated app with navigation.
 *
 * CRITICAL: The initialScreen parameter is the RESOLVED startup screen.
 * It is used as the initial value for currentScreen state.
 * This ensures no navigation corrections are needed after render.
 *
 * @param initialScreen The screen to start with - computed during bootstrap
 */
@Composable
private fun AuthenticatedApp(initialScreen: Screen) {
    // Initialize navigation state with the resolved initial screen
    // This is the KEY fix - we don't start with Screen.Home and then navigate
    var currentScreen by remember { mutableStateOf(initialScreen) }

    // Collect state needed for UI
    val groups by NostrRepository.groups.collectAsState()
    val joinedGroups by NostrRepository.joinedGroups.collectAsState()
    val unreadCounts by NostrRepository.unreadCounts.collectAsState()
    val userMetadata by NostrRepository.userMetadata.collectAsState()
    val isLoggedIn by NostrRepository.isLoggedIn.collectAsState()

    // Get pubKey reactively
    val pubKey = remember(isLoggedIn) { NostrRepository.getPublicKey() }
    val currentUserMetadata = remember(pubKey, userMetadata) {
        pubKey?.let { userMetadata[it] }
    }

    // Remember scroll states across navigation
    val homeGridState = rememberLazyGridState()
    val relayListState = rememberLazyListState()

    // Navigation handler that persists group state
    val onNavigate: (Screen) -> Unit = { newScreen ->
        currentScreen = newScreen

        // Persist navigation state for next app launch
        pubKey?.let { pk ->
            when (newScreen) {
                is Screen.Group -> {
                    SecureStorage.saveLastViewedGroup(pk, newScreen.groupId, newScreen.groupName)
                }
                is Screen.Home -> {
                    SecureStorage.clearLastViewedGroup(pk)
                }
                else -> {
                    // Other screens don't affect persisted group state
                }
            }
        }
    }

    // Determine current active group ID for server rail highlighting
    val activeGroupId = when (val screen = currentScreen) {
        is Screen.Group -> screen.groupId
        else -> null
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isDesktop = maxWidth >= 600.dp

        if (isDesktop) {
            DesktopShell(
                joinedGroups = joinedGroups,
                groups = groups,
                activeGroupId = activeGroupId,
                unreadCounts = unreadCounts,
                onHomeClick = { onNavigate(Screen.Home) },
                onGroupClick = { groupId, groupName ->
                    onNavigate(Screen.Group(groupId, groupName))
                },
                onAddClick = { onNavigate(Screen.Home) },
                userAvatarUrl = currentUserMetadata?.picture,
                userDisplayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
                userPubkey = pubKey,
                onUserClick = { onNavigate(Screen.Profile) },
                isProfileActive = currentScreen is Screen.Profile
            ) {
                DesktopContent(
                    currentScreen = currentScreen,
                    homeGridState = homeGridState,
                    relayListState = relayListState,
                    onNavigate = onNavigate
                )
            }
        } else {
            MobileContent(
                currentScreen = currentScreen,
                homeGridState = homeGridState,
                relayListState = relayListState,
                onNavigate = onNavigate
            )
        }
    }
}

/**
 * Desktop content - screens rendered inside the DesktopShell.
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
                showServerRail = false
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
                showServerRail = false
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
        is Screen.EditProfile -> {
            EditProfileScreen(onNavigate = onNavigate)
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
        is Screen.EditProfile -> {
            EditProfileScreen(onNavigate = onNavigate)
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
