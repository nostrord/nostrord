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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
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
import org.nostr.nostrord.storage.clearLastGroupForRelay
import org.nostr.nostrord.storage.getLastGroupForRelay
import org.nostr.nostrord.storage.saveLastGroupForRelay
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.components.layout.DesktopShell
import org.nostr.nostrord.ui.components.navigation.MinimalTitleBar
import org.nostr.nostrord.ui.components.navigation.NavigationToolbar
import org.nostr.nostrord.ui.components.navigation.ServerRail
import org.nostr.nostrord.ui.components.notifications.NotificationPermissionBanner
import org.nostr.nostrord.ui.components.sidebars.GroupsNavSidebar
import org.nostr.nostrord.ui.window.LocalDesktopWindowControls
import org.nostr.nostrord.ui.navigation.BrowserNavigationHandler
import org.nostr.nostrord.ui.navigation.NavigationHistory
import org.nostr.nostrord.ui.navigation.PlatformBackHandler
import org.nostr.nostrord.ui.navigation.browserGoBack
import org.nostr.nostrord.ui.navigation.browserGoForward
import org.nostr.nostrord.ui.navigation.platformHasBrowserNavigation
import org.nostr.nostrord.ui.screens.group.components.CreateGroupModal
import org.nostr.nostrord.ui.screens.group.components.JoinGroupModal
import org.nostr.nostrord.ui.screens.home.HomeScreen
import org.nostr.nostrord.ui.screens.settings.SettingsScreen
import org.nostr.nostrord.ui.screens.group.GroupScreen
import org.nostr.nostrord.ui.screens.relay.AddRelayModal
import org.nostr.nostrord.ui.screens.login.NostrLoginScreen
import org.nostr.nostrord.ui.screens.backup.BackupScreen
import org.nostr.nostrord.ui.screens.onboarding.OnboardingScreen
import org.nostr.nostrord.ui.screens.profile.EditProfileScreen
import org.nostr.nostrord.network.managers.ConnectionManager
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

    MaterialTheme(colorScheme = NostrordDarkColorScheme) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
                        deepLinkRelayUrl = startupState.deepLinkRelayUrl,
                        deepLinkInviteCode = startupState.deepLinkInviteCode
                    )
                }
            }
        }
    }
}

private val NostrordDarkColorScheme = darkColorScheme(
    primary = NostrordColors.Primary,
    onPrimary = NostrordColors.TextPrimary,
    primaryContainer = NostrordColors.PrimaryVariant,
    onPrimaryContainer = NostrordColors.TextPrimary,
    background = NostrordColors.Background,
    onBackground = NostrordColors.TextContent,
    surface = NostrordColors.Surface,
    onSurface = NostrordColors.TextContent,
    surfaceVariant = NostrordColors.SurfaceVariant,
    onSurfaceVariant = NostrordColors.TextSecondary,
    error = NostrordColors.Error,
    onError = NostrordColors.TextPrimary,
    outline = NostrordColors.Divider,
)

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
    deepLinkRelayUrl: String? = null,
    deepLinkInviteCode: String? = null
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
    val kind10009Relays by AppModule.nostrRepository.kind10009Relays.collectAsState()
    val groupTagRelays by AppModule.nostrRepository.groupTagRelays.collectAsState()
    val isLoggedIn by AppModule.nostrRepository.isLoggedIn.collectAsState()

    // Get pubKey reactively (needed for persistScreenState)
    val pubKey = remember(isLoggedIn) { AppModule.nostrRepository.getPublicKey() }

    // Remember scroll states across navigation
    val homeGridState = rememberLazyGridState()

    val currentRelayUrl by AppModule.nostrRepository.currentRelayUrl.collectAsState()
    val isDiscoveringRelays by AppModule.nostrRepository.isDiscoveringRelays.collectAsState()

    var selectedRelayUrl by remember(currentRelayUrl) { mutableStateOf(currentRelayUrl) }

    // [previousRelayUrl] must be captured before [selectedRelayUrl] is mutated by
    // the caller — reading it inside this fn would always see the new value and
    // break the same-relay toggle.
    fun resolveScreenForRelay(clickedUrl: String, previousRelayUrl: String): Screen {
        if (clickedUrl.isBlank() || clickedUrl == previousRelayUrl) return Screen.Home
        val pk = pubKey ?: return Screen.Home
        val (groupId, groupName) = SecureStorage.getLastGroupForRelay(pk, clickedUrl) ?: return Screen.Home
        return Screen.Group(groupId, groupName)
    }

    fun persistScreenState(screen: Screen) {
        pubKey?.let { pk ->
            when (screen) {
                is Screen.Group -> {
                    SecureStorage.saveLastViewedGroup(pk, screen.groupId, screen.groupName)
                    if (selectedRelayUrl.isNotBlank()) {
                        SecureStorage.saveLastGroupForRelay(
                            pk, selectedRelayUrl, screen.groupId, screen.groupName
                        )
                    }
                }
                is Screen.Home -> {
                    SecureStorage.clearLastViewedGroup(pk)
                    // A null per-relay entry means "user last on Home" — see resolveScreenForRelay.
                    if (selectedRelayUrl.isNotBlank()) {
                        SecureStorage.clearLastGroupForRelay(pk, selectedRelayUrl)
                    }
                }
                else -> {
                    // Other screens don't affect persisted group state
                }
            }
        }
    }

    // Connect to deep link relay if provided (e.g. login via /?relay=X&group=Y).
    // initialize() may have skipped the deep link because the user wasn't logged in yet.
    LaunchedEffect(deepLinkRelayUrl) {
        if (deepLinkRelayUrl != null && deepLinkRelayUrl != currentRelayUrl) {
            selectedRelayUrl = deepLinkRelayUrl
            AppModule.nostrRepository.switchRelay(deepLinkRelayUrl)
        }
        // navHistory skips onNavigate for the initial entry, so deep links never
        // hit persistScreenState. Mirror it so the URL is authoritative — a
        // group URL saves it, a relay-only URL clears the per-relay entry.
        if (deepLinkRelayUrl != null) {
            persistScreenState(initialScreen)
        }
    }

    // Set the active group on initial screen load (deep link or restored from storage).
    // onNavigate handles subsequent navigations, but the initial screen bypasses it.
    LaunchedEffect(Unit) {
        if (initialScreen is Screen.Group) {
            AppModule.nostrRepository.setActiveGroup(initialScreen.groupId)
        }
        // Web: hook document.visibilitychange + window.focus/blur → FocusTracker.
        // Other platforms are no-ops (Lifecycle observer drives them).
        org.nostr.nostrord.notifications.installPlatformFocusListeners(AppModule.focusTracker)
    }

    // Pending invite code from deep link or browser navigation.
    // Passed to GroupScreen which handles auto-join and consumption.
    var pendingInviteCode by remember { mutableStateOf(deepLinkInviteCode) }

    var showCreateGroupModal by remember { mutableStateOf(false) }
    var showJoinGroupModal by remember { mutableStateOf(false) }
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
                Lifecycle.Event.ON_RESUME  -> {
                    AppModule.nostrRepository.onForeground()
                    AppModule.focusTracker.setFocused(true)
                }
                Lifecycle.Event.ON_PAUSE   -> {
                    AppModule.nostrRepository.onBackground()
                    AppModule.focusTracker.setFocused(false)
                }
                Lifecycle.Event.ON_DESTROY -> AppModule.nostrRepository.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Explicit "r" tag relays first, then implicit group-tag relays, then current.
    val relayList = remember(currentRelayUrl, kind10009Relays, groupTagRelays) {
        (kind10009Relays.toList() + groupTagRelays.toList() + currentRelayUrl)
            .filter { it.isNotBlank() }.distinct()
    }

    LaunchedEffect(relayList) {
        if (selectedRelayUrl !in relayList) {
            selectedRelayUrl = relayList.firstOrNull() ?: currentRelayUrl
        }
    }

    val loadingRelays by AppModule.nostrRepository.loadingRelays.collectAsState()
    val isGroupsLoading = selectedRelayUrl in loadingRelays || selectedRelayUrl.isBlank()

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

    // Cross-relay group navigation (e.g. clicking a NIP-29 group naddr that points
    // to a different relay). selectedRelayUrl must be updated synchronously before
    // persistScreenState runs, otherwise the new group is saved under the previous
    // relay's lastGroupForRelay entry and the sidebar serves the wrong group when
    // the user later switches back to that relay.
    val onNavigateToGroupWithRelay: (String, String?, String?) -> Unit =
        { groupId, groupName, relayUrl ->
            if (relayUrl != null && relayUrl != selectedRelayUrl) {
                selectedRelayUrl = relayUrl
                scope.launch { AppModule.nostrRepository.switchRelay(relayUrl) }
            }
            onNavigate(Screen.Group(groupId, groupName))
        }

    // Direct history navigation — called by native platforms and by BrowserNavigationHandler.
    // Restores the relay that was active when the entry was pushed.
    val onDirectHistoryBack: () -> Unit = {
        navHistory.goBack()?.let { entry ->
            persistScreenState(entry.screen)
            AppModule.nostrRepository.setActiveGroup(
                if (entry.screen is Screen.Group) entry.screen.groupId else null
            )
            if (entry.relayUrl.isNotBlank() && entry.relayUrl != selectedRelayUrl) {
                selectedRelayUrl = entry.relayUrl
                scope.launch { AppModule.nostrRepository.switchRelay(entry.relayUrl) }
            }
        }
    }

    val onDirectHistoryForward: () -> Unit = {
        navHistory.goForward()?.let { entry ->
            persistScreenState(entry.screen)
            AppModule.nostrRepository.setActiveGroup(
                if (entry.screen is Screen.Group) entry.screen.groupId else null
            )
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

    // Route notification clicks to navigation. Only the web NotificationService actual
    // emits here (other platforms' SharedFlow never fires), so this is a no-op elsewhere.
    LaunchedEffect(Unit) {
        AppModule.notificationService.notificationClicks.collect { clickedGroupId ->
            val name = AppModule.nostrRepository.groups.value.firstOrNull { it.id == clickedGroupId }?.name
            onNavigate(Screen.Group(clickedGroupId, name))
        }
    }

    // Surface total unread count in the browser tab title: "(3) Nostrord".
    // No-op on non-web platforms.
    val totalUnread by AppModule.nostrRepository.totalUnread.collectAsState()
    LaunchedEffect(totalUnread) {
        val base = "Nostrord"
        org.nostr.nostrord.notifications.setDocumentTitle(
            if (totalUnread > 0) "($totalUnread) $base" else base
        )
    }

    // Android system back button — disabled when settings overlay is open (SettingsScreen handles it)
    PlatformBackHandler(enabled = !showSettings && navHistory.canGoBack) { onHistoryBack() }

    // Browser back/forward buttons (JS/WasmJS only, no-op on other platforms).
    // Uses URL-based navigation: on popstate, the URL is parsed and applied directly.
    BrowserNavigationHandler(
        currentScreen = currentScreen,
        selectedRelayUrl = selectedRelayUrl,
        onUrlNavigation = { relayUrl, groupId, inviteCode ->
            if (showSettings) {
                // Browser back pressed while settings overlay is open — close it instead of navigating
                showSettings = false
            } else {
                // Switch relay if different
                if (relayUrl != selectedRelayUrl) {
                    selectedRelayUrl = relayUrl
                    scope.launch { AppModule.nostrRepository.switchRelay(relayUrl) }
                }
                // Set pending invite code if present
                if (inviteCode != null) {
                    pendingInviteCode = inviteCode
                }
                // Navigate to the correct screen
                val targetScreen = if (groupId != null) {
                    Screen.Group(groupId, null)
                } else {
                    Screen.Home
                }
                if (targetScreen != currentScreen) {
                    navHistory.navigate(targetScreen, relayUrl)
                    AppModule.nostrRepository.setActiveGroup(
                        if (targetScreen is Screen.Group) targetScreen.groupId else null
                    )
                }
                // Outside the guard: a URL change can swap the relay without
                // changing the screen kind (Home → Home on another relay), and
                // we still need per-relay state to track the new URL.
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

    if (showJoinGroupModal) {
        JoinGroupModal(
            onJoin = { relayUrl, groupId, inviteCode ->
                showJoinGroupModal = false
                if (relayUrl != selectedRelayUrl) {
                    selectedRelayUrl = relayUrl
                    scope.launch { AppModule.nostrRepository.switchRelay(relayUrl) }
                }
                if (inviteCode != null) {
                    pendingInviteCode = inviteCode
                }
                onNavigate(Screen.Group(groupId, null))
            },
            onDismiss = { showJoinGroupModal = false }
        )
    }

    if (showAddRelayModal) {
        AddRelayModal(
            connectedRelays = kind10009Relays,
            onSwitchRelay = { url ->
                scope.launch {
                    AppModule.nostrRepository.addRelay(url)
                    selectedRelayUrl = url
                    onNavigate(Screen.Home)
                    showAddRelayModal = false
                    AppModule.nostrRepository.switchRelay(url)
                }
            },
            onDismiss = { showAddRelayModal = false },
            initialTab = addRelayInitialTab,
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize().then(keyEventModifier)) {
        val isDesktop = maxWidth >= 912.dp

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
                        val previousRelayUrl = selectedRelayUrl
                        selectedRelayUrl = url
                        scope.launch { AppModule.nostrRepository.switchRelay(url) }
                        onNavigate(resolveScreenForRelay(url, previousRelayUrl))
                    },
                    onRelayTitleClick = { onNavigate(Screen.Home) },
                    onAddRelayClick = { onNavigate(Screen.RelaySettings) },
                    onGroupClick = { groupId, groupName ->
                        onNavigate(Screen.Group(groupId, groupName))
                    },
                    onCreateGroupClick = { showCreateGroupModal = true },
                    onJoinGroupClick = { showJoinGroupModal = true },
                    onAddRelayFromSidebar = if (hasNoRelays) {{ addRelayInitialTab = 0; showAddRelayModal = true }} else null,
                    onUserClick = { showSettings = true },
                    isProfileActive = showSettings,
                    modifier = Modifier.weight(1f)
                ) {
                    val hideAnimatedImages = showSettings || showCreateGroupModal || showAddRelayModal
                    CompositionLocalProvider(LocalAnimatedImageHidden provides hideAnimatedImages) {
                        DesktopContent(
                            currentScreen = currentScreen,
                            selectedRelayUrl = selectedRelayUrl,
                            homeGridState = homeGridState,
                            onNavigate = onNavigate,
                            onNavigateToGroupWithRelay = onNavigateToGroupWithRelay,
                            hasNoRelays = hasNoRelays,
                            onAddRelay = { addRelayInitialTab = 0; showAddRelayModal = true },
                            onAddRelayCustomUrl = { addRelayInitialTab = 1; showAddRelayModal = true },
                            pendingInviteCode = pendingInviteCode,
                            onInviteCodeConsumed = { pendingInviteCode = null }
                        )
                    }
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
                                val previousRelayUrl = selectedRelayUrl
                                selectedRelayUrl = url
                                scope.launch {
                                    drawerState.close()
                                    AppModule.nostrRepository.switchRelay(url)
                                }
                                onNavigate(resolveScreenForRelay(url, previousRelayUrl))
                            },
                            onRelayTitleClick = {
                                scope.launch { drawerState.close() }
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
                            onJoinGroupClick = {
                                scope.launch { drawerState.close() }
                                showJoinGroupModal = true
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
                val hideAnimatedImages = drawerState.targetValue == DrawerValue.Open || showCreateGroupModal || showAddRelayModal || showSettings
                CompositionLocalProvider(LocalAnimatedImageHidden provides hideAnimatedImages) {
                    MobileContent(
                        currentScreen = currentScreen,
                        selectedRelayUrl = selectedRelayUrl,
                        homeGridState = homeGridState,
                        onNavigate = onNavigate,
                        onNavigateToGroupWithRelay = onNavigateToGroupWithRelay,
                        onCreateGroupClick = { showCreateGroupModal = true },
                        hasNoRelays = hasNoRelays,
                        onAddRelay = { addRelayInitialTab = 0; showAddRelayModal = true },
                        onAddRelayCustomUrl = { addRelayInitialTab = 1; showAddRelayModal = true },
                        onOpenDrawer = onOpenDrawer,
                        pendingInviteCode = pendingInviteCode,
                        onInviteCodeConsumed = { pendingInviteCode = null }
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

    // Floating prompt to enable desktop notifications. Mounted at the root so it
    // persists across navigation; renders only when supported + permission Default.
    NotificationPermissionBanner(modifier = Modifier.align(Alignment.TopCenter))
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
    onNavigateToGroupWithRelay: (String, String?, String?) -> Unit = { _, _, _ -> },
    hasNoRelays: Boolean = false,
    onAddRelay: () -> Unit = {},
    onAddRelayCustomUrl: () -> Unit = {},
    pendingInviteCode: String? = null,
    onInviteCodeConsumed: () -> Unit = {}
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
                onNavigateToGroup = onNavigateToGroupWithRelay,
                showServerRail = false,
                forceDesktop = true,
                pendingInviteCode = pendingInviteCode,
                onInviteCodeConsumed = onInviteCodeConsumed
            )
        }
        is Screen.EditProfile -> {
            EditProfileScreen(
                onNavigate = onNavigate,
                forceDesktop = true
            )
        }
        is Screen.NostrLogin -> {
            NostrLoginScreen {
                onNavigate(Screen.Home)
            }
        }
        is Screen.BackupPrivateKey -> BackupScreen(forceDesktop = true)
        else -> HomeScreen(relayUrl = selectedRelayUrl, gridState = homeGridState, onNavigate = onNavigate, forceDesktop = true)
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
    onNavigateToGroupWithRelay: (String, String?, String?) -> Unit = { _, _, _ -> },
    onCreateGroupClick: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    hasNoRelays: Boolean = false,
    onAddRelay: () -> Unit = {},
    onAddRelayCustomUrl: () -> Unit = {},
    pendingInviteCode: String? = null,
    onInviteCodeConsumed: () -> Unit = {}
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
                onNavigateToGroup = onNavigateToGroupWithRelay,
                onOpenDrawer = onOpenDrawer,
                pendingInviteCode = pendingInviteCode,
                onInviteCodeConsumed = onInviteCodeConsumed
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
    onRelayTitleClick: () -> Unit,
    onAddRelayClick: () -> Unit,
    onGroupClick: (groupId: String, groupName: String?) -> Unit,
    onCreateGroupClick: () -> Unit,
    onJoinGroupClick: () -> Unit = {},
    onAddRelayFromSidebar: (() -> Unit)? = null,
    onUserClick: () -> Unit = {}
) {
    val groupsByRelay by AppModule.nostrRepository.groupsByRelay.collectAsState()
    val joinedGroupsByRelay by AppModule.nostrRepository.joinedGroupsByRelay.collectAsState()
    val unreadCounts by AppModule.nostrRepository.unreadCounts.collectAsState()
    val lastMessageAt by AppModule.nostrRepository.latestMessageTimestamps.collectAsState()
    val unreadByRelay by AppModule.nostrRepository.unreadByRelay.collectAsState()
    val relayMetadata by AppModule.nostrRepository.relayMetadata.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val childrenByParentRaw by AppModule.nostrRepository.childrenByParent.collectAsState()
    val unverifiedChildrenRaw by AppModule.nostrRepository.unverifiedChildren.collectAsState()
    val subgroupsEnabled by AppModule.featureFlags.subgroupsEnabled.collectAsState()
    // See DesktopShell.kt — when the experimental flag is off, hide the hierarchy
    // in the mobile drawer too.
    val childrenByParent = if (subgroupsEnabled) childrenByParentRaw else emptyMap()
    val unverifiedChildren = if (subgroupsEnabled) unverifiedChildrenRaw else emptySet()

    val orphanedJoinedByRelay by AppModule.nostrRepository.orphanedJoinedByRelay.collectAsState()
    val fullGroupListFetchedRelays by AppModule.nostrRepository.fullGroupListFetchedRelays.collectAsState()
    val connectionState by AppModule.nostrRepository.connectionState.collectAsState()
    val isRelayConnected = connectionState is ConnectionManager.ConnectionState.Connected
    val sidebarScope = rememberCoroutineScope()

    val groupsForRelay = remember(activeRelayUrl, groupsByRelay) {
        groupsByRelay[activeRelayUrl] ?: emptyList()
    }
    val joinedGroupIds = remember(activeRelayUrl, joinedGroupsByRelay) {
        joinedGroupsByRelay[activeRelayUrl] ?: emptySet()
    }
    val orphanedJoinedIds = remember(activeRelayUrl, orphanedJoinedByRelay) {
        orphanedJoinedByRelay[activeRelayUrl] ?: emptySet()
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
            unreadByRelay = unreadByRelay,
            userAvatarUrl = currentUserMetadata?.picture,
            userDisplayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
            userPubkey = pubKey,
            onUserClick = onUserClick,
            isProfileActive = isProfileActive,
            showTooltips = false
        )
        GroupsNavSidebar(
            relayUrl = activeRelayUrl,
            groups = groupsForRelay,
            joinedGroupIds = joinedGroupIds,
            activeGroupId = activeGroupId,
            unreadCounts = unreadCounts,
            lastMessageAt = lastMessageAt,
            relayName = relayMetadata[activeRelayUrl]?.name,
            isLoading = isGroupsLoading,
            childrenByParent = childrenByParent,
            unverifiedChildren = unverifiedChildren,
            orphanedJoinedIds = orphanedJoinedIds,
            onRelayTitleClick = onRelayTitleClick,
            onGroupClick = onGroupClick,
            onCreateGroupClick = onCreateGroupClick,
            onJoinGroupClick = onJoinGroupClick,
            onAddRelay = onAddRelayFromSidebar,
            onForgetOrphan = { groupId ->
                sidebarScope.launch {
                    AppModule.nostrRepository.forgetGroup(groupId, activeRelayUrl)
                }
            },
            isGroupFetchLazy = AppModule.nostrRepository.isGroupFetchLazy(activeRelayUrl),
            hasFullGroupListBeenFetched = activeRelayUrl in fullGroupListFetchedRelays,
            onRequestFullGroupList = {
                sidebarScope.launch {
                    AppModule.nostrRepository.requestFullGroupListForRelay(activeRelayUrl)
                }
            },
            isRelayConnected = isRelayConnected
        )
    }
}
