package org.nostr.nostrord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.startup.AppStartState
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.layout.DesktopShell
import org.nostr.nostrord.ui.components.navigation.MinimalTitleBar
import org.nostr.nostrord.ui.components.navigation.NavigationToolbar
import org.nostr.nostrord.ui.components.navigation.ServerRail
import org.nostr.nostrord.ui.components.sidebars.GroupsNavSidebar
import org.nostr.nostrord.ui.window.LocalDesktopWindowControls
import org.nostr.nostrord.ui.navigation.BrowserNavigationHandler
import org.nostr.nostrord.ui.navigation.NavigationHistory
import org.nostr.nostrord.ui.navigation.PlatformBackHandler
import org.nostr.nostrord.ui.navigation.browserGoBack
import org.nostr.nostrord.ui.navigation.browserGoForward
import org.nostr.nostrord.ui.navigation.platformHasBrowserNavigation
import org.nostr.nostrord.ui.screens.group.components.CreateGroupModal
import org.nostr.nostrord.ui.screens.home.HomeScreen
import org.nostr.nostrord.ui.screens.settings.SettingsScreen
import org.nostr.nostrord.ui.screens.group.GroupScreen
import org.nostr.nostrord.ui.screens.relay.AddRelayModal
import org.nostr.nostrord.ui.screens.login.NostrLoginScreen
import org.nostr.nostrord.ui.screens.backup.BackupScreen
import org.nostr.nostrord.ui.screens.profile.EditProfileScreen
import org.nostr.nostrord.ui.screens.onboarding.OnboardingScreen
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
    val vm = viewModel { AppViewModel(AppModule.nostrRepository) }
    val isInitialized by vm.isInitialized.collectAsState()
    val isLoggedIn by vm.isLoggedIn.collectAsState()
    val isBunkerVerifying by vm.isBunkerVerifying.collectAsState()

    // Phase 2: Compute startup state synchronously from current values
    // isBunkerVerifying keeps the app in Initializing (loading) while the signer
    // confirms the restored session — avoids showing main UI before auth is confirmed.
    val startupState: AppStartState = remember(isInitialized, isLoggedIn, isBunkerVerifying) {
        if (isBunkerVerifying) AppStartState.Initializing
        else StartupResolver.resolve(isInitialized, isLoggedIn)
    }

    MaterialTheme {
        val hasWindowControls = LocalDesktopWindowControls.current != null

        // Phase 3: Render based on resolved startup state
        when (startupState) {
            is AppStartState.Initializing -> {
                val loadingMessage = when {
                    isBunkerVerifying && !isLoggedIn -> "Logging out..."
                    isBunkerVerifying -> "Reconnecting to signer..."
                    else -> null
                }
                if (hasWindowControls) {
                    Column(Modifier.fillMaxSize()) {
                        MinimalTitleBar()
                        LoadingScreen(Modifier.weight(1f), message = loadingMessage)
                    }
                } else {
                    LoadingScreen(message = loadingMessage)
                }
            }

            is AppStartState.Unauthenticated -> {
                // Not logged in - show login
                if (hasWindowControls) {
                    Column(Modifier.fillMaxSize()) {
                        MinimalTitleBar()
                        NostrLoginScreen(modifier = Modifier.weight(1f)) {
                            // After login, the startupState will recompute due to isLoggedIn change
                        }
                    }
                } else {
                    NostrLoginScreen {
                        // After login, the startupState will recompute due to isLoggedIn change
                    }
                }
            }

            is AppStartState.Authenticated -> {
                // Authenticated with resolved initial screen
                // Now we can create the navigation state with the correct initial value
                AuthenticatedApp(
                    initialScreen = startupState.initialScreen,
                    restoredFromPersistence = startupState.restoredFromPersistence
                )
            }
        }
    }
}

/**
 * Loading screen shown during bootstrap.
 */
@Composable
private fun LoadingScreen(modifier: Modifier = Modifier, message: String? = null) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NostrordColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = NostrordColors.Primary)
            if (message != null) {
                Spacer(Modifier.height(16.dp))
                Text(
                    text = message,
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
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
private fun AuthenticatedApp(initialScreen: Screen, restoredFromPersistence: Boolean) {
    // Initialize navigation history with the resolved initial screen
    val navHistory = remember {
        NavigationHistory(initialScreen).also { history ->
            if (restoredFromPersistence && initialScreen !is Screen.Home) {
                history.ensureHomeBase()
            }
        }
    }
    val currentScreen = navHistory.currentScreen

    // Collect state needed for UI
    val groups by AppModule.nostrRepository.groups.collectAsState()
    val groupsByRelay by AppModule.nostrRepository.groupsByRelay.collectAsState()
    val joinedGroups by AppModule.nostrRepository.joinedGroups.collectAsState()
    val joinedGroupsByRelay by AppModule.nostrRepository.joinedGroupsByRelay.collectAsState()
    val unreadCounts by AppModule.nostrRepository.unreadCounts.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val relayMetadata by AppModule.nostrRepository.relayMetadata.collectAsState()
    val isLoggedIn by AppModule.nostrRepository.isLoggedIn.collectAsState()

    // Get pubKey reactively
    val pubKey = remember(isLoggedIn) { AppModule.nostrRepository.getPublicKey() }
    val currentUserMetadata = remember(pubKey, userMetadata) {
        pubKey?.let { userMetadata[it] }
    }

    // Remember scroll states across navigation
    val homeGridState = rememberLazyGridState()

    val currentRelayUrl by AppModule.nostrRepository.currentRelayUrl.collectAsState()

    // selectedRelayUrl tracks which relay is browsed in the sidebar.
    // Resets when the actual active relay changes (e.g. after adding a relay in settings).
    var selectedRelayUrl by remember(currentRelayUrl) { mutableStateOf(currentRelayUrl) }

    var showCreateGroupModal by remember { mutableStateOf(false) }
    var showAddRelayModal by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Relay list — stable insertion order. The active relay does NOT jump to the top when
    // switching. New relays are appended at the end; all others keep their position.
    // currentRelayUrl is appended last only as a fallback for when it isn't in the map yet
    // (e.g. briefly after switchRelay before the first kind:39000 events arrive).
    val relayList = remember(currentRelayUrl, groupsByRelay) {
        (groupsByRelay.keys.toList() + currentRelayUrl).filter { it.isNotBlank() }.distinct()
    }

    // If the selected relay was removed, fall back to the first remaining relay
    LaunchedEffect(relayList) {
        if (selectedRelayUrl !in relayList) {
            selectedRelayUrl = relayList.firstOrNull() ?: currentRelayUrl
        }
    }

    // All groups for the relay selected in the rail (not just joined ones)
    val groupsForSelectedRelay = remember(selectedRelayUrl, groupsByRelay) {
        groupsByRelay[selectedRelayUrl] ?: emptyList()
    }

    // Joined groups for the selected relay (from persistent per-relay cache)
    val joinedGroupIdsForSelectedRelay = remember(selectedRelayUrl, joinedGroupsByRelay) {
        joinedGroupsByRelay[selectedRelayUrl] ?: emptySet()
    }

    // Persist screen state for next app launch
    fun persistScreenState(screen: Screen) {
        pubKey?.let { pk ->
            when (screen) {
                is Screen.Group -> {
                    SecureStorage.saveLastViewedGroup(pk, screen.groupId, screen.groupName)
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

    // Navigation handler that records history and persists state.
    // Screen.RelaySettings is intercepted here and shown as a modal instead of navigating.
    val onNavigate: (Screen) -> Unit = { newScreen ->
        if (newScreen is Screen.RelaySettings) {
            showAddRelayModal = true
        } else {
            navHistory.navigate(newScreen)
            persistScreenState(newScreen)
        }
    }

    // Direct history navigation — called by native platforms and by BrowserNavigationHandler
    val onDirectHistoryBack: () -> Unit = {
        navHistory.goBack()?.let { screen -> persistScreenState(screen) }
    }

    val onDirectHistoryForward: () -> Unit = {
        navHistory.goForward()?.let { screen -> persistScreenState(screen) }
    }

    // In-app back/forward — used by UI buttons and keyboard shortcuts.
    // On web: routes through browser history.back()/forward() so browser stays in sync.
    // The browser then fires popstate → BrowserNavigationHandler → onDirectHistoryBack.
    // On native: calls navHistory directly.
    val onHistoryBack: () -> Unit = {
        if (platformHasBrowserNavigation) {
            browserGoBack()
        } else {
            onDirectHistoryBack()
        }
    }

    val onHistoryForward: () -> Unit = {
        if (platformHasBrowserNavigation) {
            browserGoForward()
        } else {
            onDirectHistoryForward()
        }
    }

    // Android system back button
    PlatformBackHandler(enabled = navHistory.canGoBack) { onHistoryBack() }

    // Browser back/forward buttons (JS/WasmJS only, no-op on other platforms).
    // Uses onDirect* callbacks to avoid circular routing through browserGoBack/Forward.
    BrowserNavigationHandler(
        currentScreen = currentScreen,
        onBack = onDirectHistoryBack,
        onForward = onDirectHistoryForward
    )

    // Determine current active group ID for server rail highlighting
    val activeGroupId = when (val screen = currentScreen) {
        is Screen.Group -> screen.groupId
        else -> null
    }

    // Keyboard shortcuts: Alt+Left/Right, Cmd+[/]
    // On web, the browser handles these natively (fires popstate → BrowserNavigationHandler).
    val keyEventModifier = if (!platformHasBrowserNavigation) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                // Alt+Left or Cmd+[ → back
                (event.isAltPressed && event.key == Key.DirectionLeft) ||
                (event.isMetaPressed && event.key == Key.LeftBracket) -> {
                    onHistoryBack()
                    true
                }
                // Alt+Right or Cmd+] → forward
                (event.isAltPressed && event.key == Key.DirectionRight) ||
                (event.isMetaPressed && event.key == Key.RightBracket) -> {
                    onHistoryForward()
                    true
                }
                else -> false
            }
        }
    } else {
        Modifier
    }

    if (showCreateGroupModal) {
        CreateGroupModal(
            currentRelayUrl = selectedRelayUrl,
            onDismiss = { showCreateGroupModal = false },
            onGroupCreated = { groupId, groupName ->
                showCreateGroupModal = false
                onNavigate(Screen.Group(groupId, groupName))
            }
        )
    }

    if (showAddRelayModal) {
        AddRelayModal(
            connectedRelays = relayList.toSet(),
            relayMetadata = relayMetadata,
            onSwitchRelay = { url ->
                scope.launch { AppModule.nostrRepository.switchRelay(url) }
                selectedRelayUrl = url
                onNavigate(Screen.Home)
            },
            onDismiss = { showAddRelayModal = false }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().then(keyEventModifier)) {
        val isDesktop = maxWidth >= 600.dp

        // No relay configured — show onboarding
        if (relayList.isEmpty()) {
            OnboardingScreen(onAddRelay = { showAddRelayModal = true })
            return@BoxWithConstraints
        }

        if (isDesktop) {
            Column {
                if (!platformHasBrowserNavigation) {
                    NavigationToolbar(
                        canGoBack = navHistory.canGoBack,
                        canGoForward = navHistory.canGoForward,
                        onBack = onHistoryBack,
                        onForward = onHistoryForward
                    )
                }
                DesktopShell(
                    relays = relayList,
                    activeRelayUrl = selectedRelayUrl,
                    groupsForRelay = groupsForSelectedRelay,
                    joinedGroupIds = joinedGroupIdsForSelectedRelay,
                    activeGroupId = activeGroupId,
                    unreadCounts = unreadCounts,
                    relayMetadata = relayMetadata,
                    onRelayClick = { url ->
                        selectedRelayUrl = url
                        scope.launch { AppModule.nostrRepository.switchRelay(url) }
                        onNavigate(Screen.Home)
                    },
                    onAddRelayClick = { onNavigate(Screen.RelaySettings) },
                    onGroupClick = { groupId, groupName ->
                        onNavigate(Screen.Group(groupId, groupName))
                    },
                    onCreateGroupClick = { showCreateGroupModal = true },
                    userAvatarUrl = currentUserMetadata?.picture,
                    userDisplayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
                    userPubkey = pubKey,
                    onUserClick = { onNavigate(Screen.Profile) },
                    isProfileActive = currentScreen is Screen.Profile,
                    modifier = Modifier.weight(1f)
                ) {
                    DesktopContent(
                        currentScreen = currentScreen,
                        selectedRelayUrl = selectedRelayUrl,
                        homeGridState = homeGridState,
                        onNavigate = onNavigate
                    )
                }
            }
        } else {
            val onOpenDrawer: () -> Unit = { scope.launch { drawerState.open() } }
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    // Relay rail (72dp) + Groups sidebar fill the drawer sheet
                    ModalDrawerSheet(
                        modifier = Modifier.width(312.dp),
                        drawerContainerColor = NostrordColors.BackgroundDark
                    ) {
                        Row(Modifier.fillMaxSize()) {
                            ServerRail(
                                relays = relayList,
                                activeRelayUrl = selectedRelayUrl,
                                onRelayClick = { url ->
                                    selectedRelayUrl = url
                                    scope.launch {
                                        drawerState.close()
                                        AppModule.nostrRepository.switchRelay(url)
                                    }
                                    onNavigate(Screen.Home)
                                },
                                onAddRelayClick = {
                                    scope.launch { drawerState.close() }
                                    onNavigate(Screen.RelaySettings)
                                },
                                relayMetadata = relayMetadata,
                                userAvatarUrl = currentUserMetadata?.picture,
                                userDisplayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
                                userPubkey = pubKey,
                                onUserClick = {
                                    scope.launch { drawerState.close() }
                                    onNavigate(Screen.Profile)
                                },
                                isProfileActive = currentScreen is Screen.Profile
                            )
                            GroupsNavSidebar(
                                relayUrl = selectedRelayUrl,
                                groups = groupsForSelectedRelay,
                                joinedGroupIds = joinedGroupIdsForSelectedRelay,
                                activeGroupId = activeGroupId,
                                unreadCounts = unreadCounts,
                                relayName = relayMetadata[selectedRelayUrl]?.name,
                                onGroupClick = { groupId, groupName ->
                                    scope.launch { drawerState.close() }
                                    onNavigate(Screen.Group(groupId, groupName))
                                },
                                onCreateGroupClick = {
                                    scope.launch { drawerState.close() }
                                    showCreateGroupModal = true
                                }
                            )
                        }
                    }
                }
            ) {
                MobileContent(
                    currentScreen = currentScreen,
                    selectedRelayUrl = selectedRelayUrl,
                    homeGridState = homeGridState,
                    onNavigate = onNavigate,
                    onCreateGroupClick = { showCreateGroupModal = true },
                    onOpenDrawer = onOpenDrawer
                )
            }
        }
    } // BoxWithConstraints

    // Settings overlay — covers the entire app (rail, sidebar, content) when active
    if (currentScreen is Screen.Profile) {
        SettingsScreen(
            showToolbar = !platformHasBrowserNavigation,
            canGoBack = navHistory.canGoBack,
            canGoForward = navHistory.canGoForward,
            onHistoryBack = onHistoryBack,
            onHistoryForward = onHistoryForward,
            onClose = { onHistoryBack() },
            onNavigate = onNavigate,
            onLogout = {
                scope.launch {
                    AppModule.nostrRepository.logout()
                }
            }
        )
    }
    } // Box
}

/**
 * Desktop content - screens rendered inside the DesktopShell.
 */
@Composable
private fun DesktopContent(
    currentScreen: Screen,
    selectedRelayUrl: String,
    homeGridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onNavigate: (Screen) -> Unit
) {
    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                relayUrl = selectedRelayUrl,
                gridState = homeGridState,
                onNavigate = onNavigate,
                forceDesktop = true
            )
        }
        is Screen.Group -> {
            GroupScreen(
                groupId = screen.groupId,
                groupName = screen.groupName,
                onNavigateHome = { onNavigate(Screen.Home) },
                onNavigateToGroup = { newGroupId, newGroupName ->
                    onNavigate(Screen.Group(newGroupId, newGroupName))
                },
                showServerRail = false
            )
        }
        is Screen.EditProfile -> {
            EditProfileScreen(
                onNavigate = onNavigate
            )
        }
        is Screen.NostrLogin -> {
            NostrLoginScreen {
                onNavigate(Screen.Home)
            }
        }
        is Screen.BackupPrivateKey -> BackupScreen()
        else -> HomeScreen(relayUrl = selectedRelayUrl, gridState = homeGridState, onNavigate = onNavigate)
    }
}

/**
 * Mobile content - screens rendered directly without shell.
 */
@Composable
private fun MobileContent(
    currentScreen: Screen,
    selectedRelayUrl: String,
    homeGridState: androidx.compose.foundation.lazy.grid.LazyGridState,
    onNavigate: (Screen) -> Unit,
    onCreateGroupClick: () -> Unit = {},
    onOpenDrawer: () -> Unit = {}
) {
    when (val screen = currentScreen) {
        is Screen.Home -> {
            HomeScreen(
                relayUrl = selectedRelayUrl,
                gridState = homeGridState,
                onNavigate = onNavigate,
                onCreateGroupClick = onCreateGroupClick,
                onOpenDrawer = onOpenDrawer
            )
        }
        is Screen.Group -> {
            GroupScreen(
                groupId = screen.groupId,
                groupName = screen.groupName,
                onNavigateHome = { onNavigate(Screen.Home) },
                onNavigateToGroup = { newGroupId, newGroupName ->
                    onNavigate(Screen.Group(newGroupId, newGroupName))
                },
                onOpenDrawer = onOpenDrawer
            )
        }
        is Screen.EditProfile -> {
            EditProfileScreen(
                onNavigate = onNavigate
            )
        }
        is Screen.NostrLogin -> {
            NostrLoginScreen {
                onNavigate(Screen.Home)
            }
        }
        is Screen.BackupPrivateKey -> BackupScreen()
        else -> HomeScreen(
            relayUrl = selectedRelayUrl,
            gridState = homeGridState,
            onNavigate = onNavigate,
            onCreateGroupClick = onCreateGroupClick,
            onOpenDrawer = onOpenDrawer
        )
    }
}
