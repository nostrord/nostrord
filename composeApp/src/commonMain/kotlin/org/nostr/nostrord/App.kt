package org.nostr.nostrord

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.startup.AppStartState
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
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
import org.nostr.nostrord.ui.screens.onboarding.OnboardingScreen
import org.nostr.nostrord.ui.screens.profile.EditProfileScreen
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
                    restoredFromPersistence = startupState.restoredFromPersistence,
                    deepLinkRelayUrl = startupState.deepLinkRelayUrl
                )
            }
        }
    }
}

/** Plain background during bootstrap — HTML shell handles the spinner on web. */
@Composable
private fun LoadingScreen(modifier: Modifier = Modifier, message: String? = null) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(NostrordColors.Background),
        contentAlignment = Alignment.Center
    ) {
        if (message != null) {
            Text(
                text = message,
                color = NostrordColors.TextSecondary,
                style = MaterialTheme.typography.bodyMedium
            )
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
private fun AuthenticatedApp(
    initialScreen: Screen,
    restoredFromPersistence: Boolean,
    deepLinkRelayUrl: String? = null
) {
    // Initialize navigation history with the resolved initial screen
    val navHistory = remember {
        NavigationHistory(initialScreen, "").also { history ->
            if (restoredFromPersistence && initialScreen !is Screen.Home) {
                history.ensureHomeBase()
            }
        }
    }
    val currentScreen = navHistory.currentScreen

    // Collect only the state needed at the root level.
    // Sidebar-specific state (groups, joinedGroups, unreadCounts, userMetadata) is
    // collected inside DesktopShell / MobileDrawerContent to avoid root recomposition.
    val groupsByRelay by AppModule.nostrRepository.groupsByRelay.collectAsState()
    val kind10009Relays by AppModule.nostrRepository.kind10009Relays.collectAsState()
    val isLoggedIn by AppModule.nostrRepository.isLoggedIn.collectAsState()

    // Get pubKey reactively (needed for persistScreenState)
    val pubKey = remember(isLoggedIn) { AppModule.nostrRepository.getPublicKey() }

    // Remember scroll states across navigation
    val homeGridState = rememberLazyGridState()

    val currentRelayUrl by AppModule.nostrRepository.currentRelayUrl.collectAsState()
    val isDiscoveringRelays by AppModule.nostrRepository.isDiscoveringRelays.collectAsState()

    var selectedRelayUrl by remember(currentRelayUrl) { mutableStateOf(currentRelayUrl) }

    // Connect to deep link relay if provided (e.g. login via /?relay=X&group=Y).
    // initialize() may have skipped the deep link because the user wasn't logged in yet.
    LaunchedEffect(deepLinkRelayUrl) {
        if (deepLinkRelayUrl != null && deepLinkRelayUrl != currentRelayUrl) {
            selectedRelayUrl = deepLinkRelayUrl
            AppModule.nostrRepository.switchRelay(deepLinkRelayUrl)
        }
    }

    var showCreateGroupModal by remember { mutableStateOf(false) }
    var showAddRelayModal by remember { mutableStateOf(false) }
    var addRelayInitialTab by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    // Lifecycle integration — reconnect on foreground, persist cursors on background/destroy.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME  -> AppModule.nostrRepository.onForeground()
                Lifecycle.Event.ON_PAUSE   -> AppModule.nostrRepository.onBackground()
                Lifecycle.Event.ON_DESTROY -> AppModule.nostrRepository.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val relayList = remember(currentRelayUrl, groupsByRelay) {
        (groupsByRelay.keys.toList() + currentRelayUrl).filter { it.isNotBlank() }.distinct()
    }

    LaunchedEffect(relayList) {
        if (selectedRelayUrl !in relayList) {
            selectedRelayUrl = relayList.firstOrNull() ?: currentRelayUrl
        }
    }

    val loadingRelays by AppModule.nostrRepository.loadingRelays.collectAsState()
    val isGroupsLoading = selectedRelayUrl in loadingRelays || selectedRelayUrl.isBlank()

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
        if (newScreen is Screen.Profile) {
            showSettings = true
        } else if (newScreen is Screen.RelaySettings) {
            addRelayInitialTab = 0
            showAddRelayModal = true
        } else {
            navHistory.navigate(newScreen, selectedRelayUrl)
            persistScreenState(newScreen)
            // Promote the relay of the opened group to ACTIVE priority for faster reconnect backoff.
            // Clear it when navigating away from a group so the relay reverts to BACKGROUND.
            AppModule.nostrRepository.setActiveGroup(
                if (newScreen is Screen.Group) newScreen.groupId else null
            )
        }
    }

    // Direct history navigation — called by native platforms and by BrowserNavigationHandler.
    // Restores the relay that was active when the entry was pushed.
    val onDirectHistoryBack: () -> Unit = {
        navHistory.goBack()?.let { entry ->
            persistScreenState(entry.screen)
            if (entry.relayUrl.isNotBlank() && entry.relayUrl != selectedRelayUrl) {
                selectedRelayUrl = entry.relayUrl
                scope.launch { AppModule.nostrRepository.switchRelay(entry.relayUrl) }
            }
        }
    }

    val onDirectHistoryForward: () -> Unit = {
        navHistory.goForward()?.let { entry ->
            persistScreenState(entry.screen)
            if (entry.relayUrl.isNotBlank() && entry.relayUrl != selectedRelayUrl) {
                selectedRelayUrl = entry.relayUrl
                scope.launch { AppModule.nostrRepository.switchRelay(entry.relayUrl) }
            }
        }
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
    // Uses URL-based navigation: on popstate, the URL is parsed and applied directly.
    BrowserNavigationHandler(
        currentScreen = currentScreen,
        selectedRelayUrl = selectedRelayUrl,
        onUrlNavigation = { relayUrl, groupId ->
            // Switch relay if different
            if (relayUrl != selectedRelayUrl) {
                selectedRelayUrl = relayUrl
                scope.launch { AppModule.nostrRepository.switchRelay(relayUrl) }
            }
            // Navigate to the correct screen
            val targetScreen = if (groupId != null) {
                Screen.Group(groupId, null)
            } else {
                Screen.Home
            }
            if (targetScreen != currentScreen) {
                navHistory.navigate(targetScreen, relayUrl)
                persistScreenState(targetScreen)
            }
        }
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
            userRelays = kind10009Relays,
            onDismiss = { showCreateGroupModal = false },
            onGroupCreated = { groupId, groupName ->
                showCreateGroupModal = false
                onNavigate(Screen.Group(groupId, groupName))
            }
        )
    }

    if (showAddRelayModal) {
        AddRelayModal(
            connectedRelays = kind10009Relays,
            onSwitchRelay = { url ->
                scope.launch {
                    AppModule.nostrRepository.addRelay(url)
                    AppModule.nostrRepository.switchRelay(url)
                    selectedRelayUrl = url
                    onNavigate(Screen.Home)
                    showAddRelayModal = false
                }
            },
            onDismiss = { showAddRelayModal = false },
            initialTab = addRelayInitialTab,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().then(keyEventModifier)) {
        val isDesktop = maxWidth >= 600.dp

        val hasNoRelays = relayList.isEmpty() && !isDiscoveringRelays

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
                    activeGroupId = activeGroupId,
                    isGroupsLoading = isGroupsLoading,
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
                    onAddRelayFromSidebar = if (hasNoRelays) {{ addRelayInitialTab = 0; showAddRelayModal = true }} else null,
                    onUserClick = { showSettings = true },
                    isProfileActive = showSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    DesktopContent(
                        currentScreen = currentScreen,
                        selectedRelayUrl = selectedRelayUrl,
                        homeGridState = homeGridState,
                        onNavigate = onNavigate,
                        hasNoRelays = hasNoRelays,
                        onAddRelay = { addRelayInitialTab = 0; showAddRelayModal = true },
                        onAddRelayCustomUrl = { addRelayInitialTab = 1; showAddRelayModal = true }
                    )
                }
            }
        } else {
            val onOpenDrawer: () -> Unit = { scope.launch { drawerState.open() } }
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        modifier = Modifier.width(312.dp),
                        drawerContainerColor = NostrordColors.BackgroundDark
                    ) {
                        MobileDrawerContent(
                            relays = relayList,
                            activeRelayUrl = selectedRelayUrl,
                            activeGroupId = activeGroupId,
                            isGroupsLoading = isGroupsLoading,
                            hasNoRelays = hasNoRelays,
                            isProfileActive = showSettings,
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
                            onGroupClick = { groupId, groupName ->
                                scope.launch { drawerState.close() }
                                onNavigate(Screen.Group(groupId, groupName))
                            },
                            onCreateGroupClick = {
                                scope.launch { drawerState.close() }
                                showCreateGroupModal = true
                            },
                            onAddRelayFromSidebar = if (hasNoRelays) {{
                                scope.launch { drawerState.close() }
                                addRelayInitialTab = 0; showAddRelayModal = true
                            }} else null,
                            onUserClick = {
                                scope.launch { drawerState.close() }
                                showSettings = true
                            }
                        )
                    }
                }
            ) {
                val hideAnimatedImages = drawerState.targetValue == DrawerValue.Open || showCreateGroupModal || showAddRelayModal
                CompositionLocalProvider(LocalAnimatedImageHidden provides hideAnimatedImages) {
                    MobileContent(
                        currentScreen = currentScreen,
                        selectedRelayUrl = selectedRelayUrl,
                        homeGridState = homeGridState,
                        onNavigate = onNavigate,
                        onCreateGroupClick = { showCreateGroupModal = true },
                        hasNoRelays = hasNoRelays,
                        onAddRelay = { addRelayInitialTab = 0; showAddRelayModal = true },
                        onAddRelayCustomUrl = { addRelayInitialTab = 1; showAddRelayModal = true },
                        onOpenDrawer = onOpenDrawer
                    )
                }
            }
        }
    } // BoxWithConstraints

    // Settings overlay — covers the entire app (rail, sidebar, content) when active
    if (showSettings) {
        SettingsScreen(
            showToolbar = !platformHasBrowserNavigation,
            canGoBack = navHistory.canGoBack,
            canGoForward = navHistory.canGoForward,
            onHistoryBack = onHistoryBack,
            onHistoryForward = onHistoryForward,
            onClose = { showSettings = false },
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
    onNavigate: (Screen) -> Unit,
    hasNoRelays: Boolean = false,
    onAddRelay: () -> Unit = {},
    onAddRelayCustomUrl: () -> Unit = {}
) {
    when (val screen = currentScreen) {
        is Screen.Home -> {
            if (hasNoRelays) {
                OnboardingScreen(
                    onAddRelay = onAddRelay,
                    onAddRelayCustomUrl = onAddRelayCustomUrl
                )
            } else {
                HomeScreen(
                    relayUrl = selectedRelayUrl,
                    gridState = homeGridState,
                    onNavigate = onNavigate,
                    forceDesktop = true
                )
            }
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
    onOpenDrawer: () -> Unit = {},
    hasNoRelays: Boolean = false,
    onAddRelay: () -> Unit = {},
    onAddRelayCustomUrl: () -> Unit = {}
) {
    when (val screen = currentScreen) {
        is Screen.Home -> {
            if (hasNoRelays) {
                OnboardingScreen(
                    onAddRelay = onAddRelay,
                    onAddRelayCustomUrl = onAddRelayCustomUrl
                )
            } else {
                HomeScreen(
                    relayUrl = selectedRelayUrl,
                    gridState = homeGridState,
                    onNavigate = onNavigate,
                    onCreateGroupClick = onCreateGroupClick,
                    onOpenDrawer = onOpenDrawer
                )
            }
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

/**
 * Mobile drawer content — collects its own sidebar state so changes don't
 * recompose the parent AuthenticatedApp or the content area.
 */
@Composable
private fun MobileDrawerContent(
    relays: List<String>,
    activeRelayUrl: String,
    activeGroupId: String?,
    isGroupsLoading: Boolean,
    hasNoRelays: Boolean,
    isProfileActive: Boolean,
    onRelayClick: (String) -> Unit,
    onAddRelayClick: () -> Unit,
    onGroupClick: (groupId: String, groupName: String?) -> Unit,
    onCreateGroupClick: () -> Unit,
    onAddRelayFromSidebar: (() -> Unit)? = null,
    onUserClick: () -> Unit = {}
) {
    val groupsByRelay by AppModule.nostrRepository.groupsByRelay.collectAsState()
    val joinedGroupsByRelay by AppModule.nostrRepository.joinedGroupsByRelay.collectAsState()
    val unreadCounts by AppModule.nostrRepository.unreadCounts.collectAsState()
    val relayMetadata by AppModule.nostrRepository.relayMetadata.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()

    val groupsForRelay = remember(activeRelayUrl, groupsByRelay) {
        groupsByRelay[activeRelayUrl] ?: emptyList()
    }
    val joinedGroupIds = remember(activeRelayUrl, joinedGroupsByRelay) {
        joinedGroupsByRelay[activeRelayUrl] ?: emptySet()
    }

    val pubKey = remember { AppModule.nostrRepository.getPublicKey() }
    val currentUserMetadata = remember(pubKey, userMetadata) {
        pubKey?.let { userMetadata[it] }
    }

    Row(Modifier.fillMaxSize()) {
        ServerRail(
            relays = relays,
            activeRelayUrl = activeRelayUrl,
            onRelayClick = onRelayClick,
            onAddRelayClick = onAddRelayClick,
            relayMetadata = relayMetadata,
            userAvatarUrl = currentUserMetadata?.picture,
            userDisplayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
            userPubkey = pubKey,
            onUserClick = onUserClick,
            isProfileActive = isProfileActive
        )
        GroupsNavSidebar(
            relayUrl = activeRelayUrl,
            groups = groupsForRelay,
            joinedGroupIds = joinedGroupIds,
            activeGroupId = activeGroupId,
            unreadCounts = unreadCounts,
            relayName = relayMetadata[activeRelayUrl]?.name,
            isLoading = isGroupsLoading,
            onGroupClick = onGroupClick,
            onCreateGroupClick = onCreateGroupClick,
            onAddRelay = onAddRelayFromSidebar
        )
    }
}
