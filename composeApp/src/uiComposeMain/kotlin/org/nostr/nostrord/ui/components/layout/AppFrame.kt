package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.auth.removeAccountBusyLabel
import org.nostr.nostrord.auth.removeAccountConfirmLabel
import org.nostr.nostrord.auth.removeAccountDialogBody
import org.nostr.nostrord.auth.removeAccountDialogTitle
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.accounts.AddAccountSheet
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.forms.AppSegmentedTabs
import org.nostr.nostrord.ui.components.forms.SegmentedTab
import org.nostr.nostrord.ui.components.loading.SkeletonCircle
import org.nostr.nostrord.ui.components.loading.SkeletonLine
import org.nostr.nostrord.ui.components.zap.ZapModalHost
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.HashRoute
import org.nostr.nostrord.ui.navigation.HomeRoute
import org.nostr.nostrord.ui.navigation.HomeTab
import org.nostr.nostrord.ui.navigation.LocalFrameNavigator
import org.nostr.nostrord.ui.navigation.NotificationsRoute
import org.nostr.nostrord.ui.navigation.RelayRoute
import org.nostr.nostrord.ui.navigation.SettingsRoute
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.dm.DmPageScreen
import org.nostr.nostrord.ui.screens.group.GroupScreen
import org.nostr.nostrord.ui.screens.group.components.AddGroupModal
import org.nostr.nostrord.ui.screens.group.components.CreateGroupModal
import org.nostr.nostrord.ui.screens.group.components.JoinGroupModal
import org.nostr.nostrord.ui.screens.group.components.UserProfileModal
import org.nostr.nostrord.ui.screens.home.Friend
import org.nostr.nostrord.ui.screens.home.HomePageScreen
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.ui.screens.notifications.NotificationsPage
import org.nostr.nostrord.ui.screens.notifications.NotificationsSidebar
import org.nostr.nostrord.ui.screens.notifications.NotificationsViewModel
import org.nostr.nostrord.ui.screens.profile.ProfilePageScreen
import org.nostr.nostrord.ui.screens.relay.RelayPageScreen
import org.nostr.nostrord.ui.screens.settings.SettingsScreen
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * New-design logged-in frame (prototype AppShell): the 72px groups rail (home,
 * joined groups with unread badges, add, DMs and notifications) plus the 240px
 * sidebar (home hub or the open group's sidebar, with the account bar) around the
 * content. Owns the new-design navigation: home vs an open group ([GroupRoute]),
 * mirroring the web's #/g/<relay>/<groupId> hash. Below md (768dp) the rail +
 * sidebar collapse into a ModalNavigationDrawer opened from each screen's hamburger.
 */
@Composable
fun AppFrame() {
    // HomePageViewModel re-arms its per-account state when the active account changes
    // (it observes repo.activePubkey), so a single long-lived instance is correct here.
    val vm = viewModel { HomePageViewModel(AppModule.nostrRepository, AppModule.notificationHistoryStore) }
    val groups by vm.myGroups.collectAsState()
    val unreadCounts by vm.unreadCounts.collectAsState()
    val notificationUnread by vm.notificationUnread.collectAsState()
    val dmUnread by AppModule.nostrRepository.totalDmUnread.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var addGroupStep by remember { mutableStateOf<AddGroupStep?>(null) }
    // Friend tapped in the home sidebar: open the quick profile modal first (no
    // chat composer around, so no Mention action), with "View profile" inside it
    // for the full page.
    var profileUser by remember { mutableStateOf<String?>(null) }
    // Notifications page open over the content, with the filter sidebar; one shared VM
    // keeps the sidebar filters and the list in sync.
    val notifVm = viewModel { NotificationsViewModel(AppModule.nostrRepository) }
    var showNotifications by remember { mutableStateOf(false) }
    var route by remember { mutableStateOf<HashRoute?>(null) }
    val groupRoute = route as? GroupRoute

    // Mirror the legacy AppShell: cross-relay navigation switches the relay first,
    // and the open group is tracked for notification suppression + unread clearing.
    LaunchedEffect(groupRoute) {
        val r = groupRoute
        if (r != null && r.relayUrl != AppModule.nostrRepository.currentRelayUrl.value) {
            AppModule.nostrRepository.switchRelay(r.relayUrl)
        }
        AppModule.nostrRepository.setActiveGroup(r?.groupId)
        if (r != null) AppModule.nostrRepository.markGroupAsRead(r.groupId)
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()
    val closeDrawer: () -> Unit = { drawerScope.launch { drawerState.close() } }

    // The 72px rail + 240px sidebar, shared by the desktop Row and the mobile drawer.
    val railContent: @Composable () -> Unit = {
        Column(
            modifier =
            Modifier
                .width(72.dp)
                .fillMaxHeight()
                .background(NostrordColors.BackgroundDark)
                .padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Top cluster fills all remaining height so the divider + DMs +
            // notifications below it stay pinned to the bottom of the rail.
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RailButton(icon = Icons.Default.Home, label = "Home", active = (route == null || route is HomeRoute) && !showNotifications) {
                    route = null
                    showNotifications = false
                    closeDrawer()
                }
                Column(
                    modifier =
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    groups.forEach { group ->
                        RailGroupButton(
                            name = group.meta.name ?: group.meta.id,
                            picture = group.meta.picture,
                            groupId = group.meta.id,
                            unread = unreadCounts[group.meta.id] ?: 0,
                            active = groupRoute?.groupId == group.meta.id && !showNotifications,
                        ) {
                            route = GroupRoute(group.relayUrl, group.meta.id)
                            showNotifications = false
                            closeDrawer()
                        }
                    }
                }
                RailButton(icon = Icons.Default.Add, label = "Add group") {
                    addGroupStep = AddGroupStep.CHOOSER
                    closeDrawer()
                }
            }
            HorizontalDivider(modifier = Modifier.width(32.dp), color = NostrordColors.Divider)
            Box {
                RailButton(icon = Icons.Default.Mail, label = "Direct messages", active = route is DmRoute && !showNotifications) {
                    route = DmRoute()
                    showNotifications = false
                    closeDrawer()
                }
                if (dmUnread > 0) {
                    RailBadge(
                        count = dmUnread,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
            Box {
                RailButton(
                    icon = Icons.Default.Notifications,
                    label = "Notifications",
                    active = showNotifications,
                ) {
                    showNotifications = true
                    closeDrawer()
                }
                if (notificationUnread > 0) {
                    RailBadge(
                        count = notificationUnread,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
        }
    }

    val sidebarContent: @Composable () -> Unit = {
        Column(
            modifier =
            Modifier
                .width(240.dp)
                .fillMaxHeight()
                .background(NostrordColors.Surface),
        ) {
            if (showNotifications) {
                Box(modifier = Modifier.weight(1f)) {
                    NotificationsSidebar(vm = notifVm)
                }
            } else if (groupRoute != null) {
                Box(modifier = Modifier.weight(1f)) {
                    GroupSidebar(
                        route = groupRoute,
                        onNavigateGroup = {
                            route = it
                            closeDrawer()
                        },
                        onNavigateHome = {
                            route = null
                            closeDrawer()
                        },
                    )
                }
            } else if (route is DmRoute) {
                Box(modifier = Modifier.weight(1f)) {
                    DmSidebar(
                        onOpenConversation = {
                            route = it
                            closeDrawer()
                        },
                        activePubkey = (route as DmRoute).pubkey,
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "nostrord",
                        color = NostrordColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                HorizontalDivider(color = NostrordColors.Divider)
                HomeHub(
                    vm = vm,
                    onOpenUser = {
                        profileUser = it
                        closeDrawer()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            AccountBar(
                onOpenSettings = { showSettings = true },
                onViewProfile = { pubkey -> route = UserRoute(pubkey) },
            )
        }
    }

    val contentArea: @Composable (forceDesktop: Boolean, onOpenDrawer: (() -> Unit)?) -> Unit = { forceDesktop, onOpenDrawer ->
        if (showNotifications) {
            NotificationsPage(
                vm = notifVm,
                onOpenGroupAtRelay = { gid, _, relay, _ ->
                    route = GroupRoute(relay, gid)
                    showNotifications = false
                },
                onOpenDrawer = onOpenDrawer,
            )
        } else {
            FrameContent(
                route = route,
                forceDesktop = forceDesktop,
                onNavigate = { route = it },
                onSelectHomeTab = { tab -> route = if (tab == HomeTab.Groups) null else HomeRoute(tab) },
                onCloseGroup = { route = null },
                onConsumeInvite = { route = (route as? GroupRoute)?.copy(inviteCode = null) },
                onEditProfile = { showSettings = true },
                onCreateGroup = { addGroupStep = AddGroupStep.CREATE },
                onJoinGroup = { addGroupStep = AddGroupStep.JOIN },
                onOpenNotifications = { showNotifications = true },
                onOpenDrawer = onOpenDrawer,
            )
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        // Below md (768) the rail + sidebar collapse into a slide-over drawer; a hamburger
        // in each screen header opens it. At md+ they're persistent columns.
        if (maxWidth < 768.dp) {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = {
                    ModalDrawerSheet(
                        drawerContainerColor = NostrordColors.BackgroundDark,
                        modifier = Modifier.width(312.dp),
                    ) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            railContent()
                            sidebarContent()
                        }
                    }
                },
            ) {
                contentArea(false) { drawerScope.launch { drawerState.open() } }
            }
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                railContent()
                sidebarContent()
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    contentArea(true, null)
                }
            }
        }

        // Add-group flow (rail "+"): the new-design chooser, then the existing
        // create / join modals, landing on the group's chat afterwards.
        when (addGroupStep) {
            AddGroupStep.CHOOSER ->
                AddGroupModal(
                    onJoin = { addGroupStep = AddGroupStep.JOIN },
                    onCreate = { addGroupStep = AddGroupStep.CREATE },
                    onDismiss = { addGroupStep = null },
                )
            AddGroupStep.CREATE -> {
                val currentRelayUrl by AppModule.nostrRepository.currentRelayUrl.collectAsState()
                val kind10009Relays by AppModule.nostrRepository.kind10009Relays.collectAsState()
                CreateGroupModal(
                    currentRelayUrl = currentRelayUrl,
                    userRelays = kind10009Relays,
                    onDismiss = { addGroupStep = null },
                    onGroupCreated = { relayUrl, groupId, _ ->
                        addGroupStep = null
                        route = GroupRoute(relayUrl, groupId)
                    },
                )
            }
            AddGroupStep.JOIN ->
                JoinGroupModal(
                    onJoin = { relayUrl, groupId, inviteCode ->
                        addGroupStep = null
                        // Open the group; the route effect switches relays and the
                        // group screen consumes the invite code (auto-join). Normalized
                        // so the route matches the relay keys used everywhere else.
                        route = GroupRoute(relayUrl.normalizeRelayUrl(), groupId, inviteCode)
                    },
                    onDismiss = { addGroupStep = null },
                )
            null -> {}
        }

        // Quick profile modal for a friend tapped in the home sidebar. No onMention
        // (we're not in a group chat, so the Mention row stays hidden); "View profile"
        // and "Message" route through the frame navigator to the full page / DM.
        profileUser?.let { pubkey ->
            val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
            CompositionLocalProvider(LocalFrameNavigator provides { route = it }) {
                UserProfileModal(
                    pubkey = pubkey,
                    metadata = userMetadata[pubkey],
                    userMetadata = userMetadata,
                    onUserClick = { profileUser = it },
                    onDismiss = { profileUser = null },
                )
            }
        }

        // Legacy Settings overlay, reachable from the account bar's gear until the
        // new-design settings page is ported. Its internal confirm runs before
        // onLogout, so the sign-out here is immediate.
        if (showSettings) {
            SettingsScreen(
                onClose = { showSettings = false },
                onNavigate = { showSettings = false },
                onLogout = {
                    showSettings = false
                    AppModule.accountStore.activeId.value?.let {
                        AppModule.accountManager.removeAccountAsync(it)
                    }
                },
            )
        }

        // Zap modal host: mounted once so any ZapController.request(...) from a profile,
        // profile modal or message renders the send-zap modal over the frame.
        ZapModalHost()
    }
}

/** Home page, the open group's chat (the legacy GroupScreen, full-featured) or a profile. */
@Composable
private fun FrameContent(
    route: HashRoute?,
    forceDesktop: Boolean,
    onNavigate: (HashRoute) -> Unit,
    onSelectHomeTab: (HomeTab) -> Unit,
    onCloseGroup: () -> Unit,
    onConsumeInvite: () -> Unit,
    onEditProfile: () -> Unit,
    onCreateGroup: () -> Unit,
    onJoinGroup: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
) {
    CompositionLocalProvider(LocalFrameNavigator provides onNavigate) {
        when (route) {
            null, is HomeRoute ->
                HomePageScreen(
                    tab = (route as? HomeRoute)?.tab ?: HomeTab.Groups,
                    onSelectTab = onSelectHomeTab,
                    onOpenGroup = { onNavigate(GroupRoute(it.relayUrl, it.meta.id)) },
                    onOpenRelay = { onNavigate(RelayRoute(it)) },
                    onCreateGroup = onCreateGroup,
                    onJoinGroup = onJoinGroup,
                    onOpenDms = { onNavigate(DmRoute()) },
                    onOpenNotifications = onOpenNotifications,
                    onOpenDrawer = onOpenDrawer,
                )
            is UserRoute ->
                ProfilePageScreen(
                    pubkey = route.pubkey,
                    onOpenGroup = onNavigate,
                    onEditProfile = onEditProfile,
                )
            is DmRoute -> DmPageScreen(pubkey = route.pubkey, onOpenProfile = onNavigate)
            // Native opens notifications and settings through full-screen overlays
            // (showNotifications / showSettings), not the route (there is no URL bar), so
            // these arms are unreachable; the web hash router is the only producer of
            // NotificationsRoute / SettingsRoute.
            is NotificationsRoute -> {}
            is SettingsRoute -> {}
            is RelayRoute ->
                RelayPageScreen(
                    relayUrl = route.relayUrl,
                    onBack = onCloseGroup,
                    onOpenGroup = { relay, gid -> onNavigate(GroupRoute(relay, gid)) },
                )
            is GroupRoute -> {
                val groupsByRelay by AppModule.nostrRepository.groupsByRelay.collectAsState()
                val name = groupsByRelay[route.relayUrl]?.firstOrNull { it.id == route.groupId }?.name
                GroupScreen(
                    groupId = route.groupId,
                    groupName = name,
                    onNavigateHome = onCloseGroup,
                    onNavigateToGroup = { gid, _, relay -> onNavigate(GroupRoute(relay ?: route.relayUrl, gid)) },
                    showServerRail = false,
                    forceDesktop = forceDesktop,
                    onOpenDrawer = onOpenDrawer ?: {},
                    pendingInviteCode = route.inviteCode,
                    onInviteCodeConsumed = onConsumeInvite,
                )
            }
        }
    }
}

@Composable
private fun RailButton(
    icon: ImageVector,
    label: String,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = active || isHovered
    Surface(
        modifier =
        Modifier
            .size(48.dp)
            .clip(if (highlighted) NostrordShapes.shapeLarge else NostrordShapes.shapeXLarge)
            .hoverable(interactionSource),
        shape = if (highlighted) NostrordShapes.shapeLarge else NostrordShapes.shapeXLarge,
        color = if (highlighted) NostrordColors.Primary else NostrordColors.Background,
        onClick = onClick,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (highlighted) Color.White else NostrordColors.TextPrimary,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

@Composable
private fun RailGroupButton(
    name: String,
    picture: String?,
    groupId: String,
    unread: Int,
    active: Boolean = false,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val highlighted = active || isHovered
    Box {
        Surface(
            modifier =
            Modifier
                .size(48.dp)
                .clip(if (highlighted) NostrordShapes.shapeLarge else NostrordShapes.shapeXLarge)
                .hoverable(interactionSource),
            shape = if (highlighted) NostrordShapes.shapeLarge else NostrordShapes.shapeXLarge,
            color = NostrordColors.Background,
            onClick = onClick,
        ) {
            OptimizedSmallAvatar(
                imageUrl = picture,
                identifier = groupId,
                displayName = name,
                size = 48.dp,
                shape = if (highlighted) NostrordShapes.shapeLarge else NostrordShapes.shapeXLarge,
                isGroup = true,
            )
        }
        if (unread > 0) {
            RailBadge(count = unread, modifier = Modifier.align(Alignment.BottomEnd))
        }
    }
}

@Composable
private fun RailBadge(
    count: Int,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
        modifier
            .clip(CircleShape)
            .background(NostrordColors.BadgeBackground)
            .padding(horizontal = 5.dp, vertical = 1.dp),
    ) {
        Text(
            if (count > 99) "99+" else "$count",
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun HomeHub(
    vm: HomePageViewModel,
    onOpenUser: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var hub by remember { mutableStateOf(0) }
    val friends by vm.friends.collectAsState()
    val friendsLoading by vm.friendsLoading.collectAsState()
    Column(
        modifier = modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(8.dp),
    ) {
        AppSegmentedTabs(
            tabs =
            listOf(
                SegmentedTab("Friends", Icons.Default.People),
                SegmentedTab("Saved", Icons.Default.Bookmark),
            ),
            selectedIndex = hub,
            onSelect = { hub = it },
        )
        Spacer(modifier = Modifier.height(12.dp))
        when {
            hub == 1 ->
                Text(
                    "Nothing saved yet.",
                    color = NostrordColors.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            friendsLoading ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    repeat(6) { FriendRowSkeleton() }
                }
            friends.isEmpty() ->
                Text(
                    "You don't follow anyone yet.",
                    color = NostrordColors.TextMuted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                )
            else ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    friends.forEach { friend -> FriendRow(friend = friend, onClick = { onOpenUser(friend.pubkey) }) }
                }
        }
    }
}

@Composable
private fun FriendRow(
    friend: Friend,
    onClick: () -> Unit,
) {
    val name =
        friend.metadata?.displayName?.takeIf { it.isNotBlank() }
            ?: friend.metadata?.name?.takeIf { it.isNotBlank() }
            ?: (Nip19.encodeNpub(friend.pubkey).take(12) + "…")
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeMedium)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OptimizedSmallAvatar(
            imageUrl = friend.metadata?.picture,
            identifier = friend.pubkey,
            displayName = name,
            size = 32.dp,
            shape = CircleShape,
        )
        Text(
            name,
            color = NostrordColors.TextPrimary,
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Placeholder row shown while the contact list / metadata are still loading. */
@Composable
private fun FriendRowSkeleton() {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SkeletonCircle(size = 32.dp)
        SkeletonLine(width = 120.dp, height = 12.dp)
    }
}

@Composable
private fun AccountBar(onOpenSettings: () -> Unit, onViewProfile: (String) -> Unit) {
    val accounts by AppModule.accountStore.accounts.collectAsState()
    val activeId by AppModule.accountStore.activeId.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val active = accounts.firstOrNull { it.id == activeId }
    val meta = active?.pubkey?.let { userMetadata[it] }
    val displayName =
        meta?.displayName?.takeIf { it.isNotBlank() }
            ?: meta?.name?.takeIf { it.isNotBlank() }
            ?: active?.label
            ?: "Account"

    var menuOpen by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }
    var showAddAccount by remember { mutableStateOf(false) }

    AddAccountSheet(
        visible = showAddAccount,
        onDismiss = { showAddAccount = false },
        onAdded = { showAddAccount = false },
    )

    if (confirmLogout && active != null) {
        val fallbackLabel =
            accounts.filter { it.id != active.id }.maxByOrNull { it.addedAt }?.label
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { if (!isBusy) confirmLogout = false },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text(removeAccountDialogTitle(isActive = true, accountLabel = displayName)) },
            text = {
                Text(
                    removeAccountDialogBody(
                        isActive = true,
                        accountLabel = displayName,
                        fallbackLabel = fallbackLabel,
                        method = active.authMethod,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(
                    onClick = {
                        if (isBusy) return@TextButton
                        isBusy = true
                        AppModule.accountManager.removeAccountAsync(active.id) {
                            isBusy = false
                            confirmLogout = false
                        }
                    },
                    enabled = !isBusy,
                ) {
                    Text(
                        if (isBusy) removeAccountBusyLabel(isActive = true) else removeAccountConfirmLabel(isActive = true),
                        color = NostrordColors.Error,
                    )
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { confirmLogout = false }, enabled = !isBusy) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            },
        )
    }

    Box {
        // Account switcher popover (prototype AccountMenu): accounts with npub +
        // signer chip, add account, and the sign-out action.
        DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false },
            containerColor = NostrordColors.BackgroundFloating,
        ) {
            Text(
                "ACCOUNTS",
                color = NostrordColors.TextMuted,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.5.sp,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            )
            accounts.forEach { account ->
                val isActiveAccount = account.id == activeId
                val m = userMetadata[account.pubkey]
                val name =
                    m?.displayName?.takeIf { it.isNotBlank() }
                        ?: m?.name?.takeIf { it.isNotBlank() }
                        ?: account.label
                val npub = runCatching { Nip19.encodeNpub(account.pubkey) }.getOrDefault("")
                DropdownMenuItem(
                    onClick = {
                        menuOpen = false
                        if (!isActiveAccount) {
                            AppModule.accountManager.switchAccountAsync(account.id)
                        }
                    },
                    leadingIcon = {
                        OptimizedSmallAvatar(
                            imageUrl = m?.picture,
                            identifier = account.pubkey,
                            displayName = name,
                            size = 36.dp,
                            shape = CircleShape,
                        )
                    },
                    text = {
                        Column(modifier = Modifier.widthIn(max = 168.dp)) {
                            Text(
                                name,
                                color = NostrordColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                npub,
                                color = NostrordColors.TextMuted,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    trailingIcon = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = NostrordShapes.shapeSmall,
                                color = NostrordColors.BackgroundDark,
                            ) {
                                Text(
                                    signerLabel(account.authMethod),
                                    color = NostrordColors.TextMuted,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                            if (isActiveAccount) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Active",
                                    tint = NostrordColors.Success,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    },
                )
            }
            HorizontalDivider(color = NostrordColors.Divider)
            active?.let { acct ->
                DropdownMenuItem(
                    onClick = {
                        menuOpen = false
                        onViewProfile(acct.pubkey)
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = NostrordColors.TextSecondary,
                            modifier = Modifier.size(18.dp),
                        )
                    },
                    text = { Text("View profile", color = NostrordColors.TextSecondary, fontSize = 14.sp) },
                )
            }
            DropdownMenuItem(
                onClick = {
                    menuOpen = false
                    showAddAccount = true
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        tint = NostrordColors.TextSecondary,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = { Text("Add account", color = NostrordColors.TextSecondary, fontSize = 14.sp) },
            )
            DropdownMenuItem(
                onClick = {
                    menuOpen = false
                    confirmLogout = true
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Logout,
                        contentDescription = null,
                        tint = NostrordColors.Error,
                        modifier = Modifier.size(18.dp),
                    )
                },
                text = { Text("Log out of $displayName", color = NostrordColors.Error, fontSize = 14.sp) },
            )
        }

        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(52.dp)
                .background(NostrordColors.BackgroundFloating)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val triggerInteraction = remember { MutableInteractionSource() }
            val triggerHovered by triggerInteraction.collectIsHoveredAsState()
            Row(
                modifier =
                Modifier
                    .weight(1f)
                    .clip(NostrordShapes.shapeSmall)
                    .background(if (triggerHovered) NostrordColors.HoverBackground else Color.Transparent)
                    .hoverable(triggerInteraction)
                    .clickable { menuOpen = !menuOpen }
                    .padding(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OptimizedSmallAvatar(
                    imageUrl = meta?.picture,
                    identifier = active?.pubkey ?: "",
                    displayName = displayName,
                    size = 32.dp,
                    shape = CircleShape,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        displayName,
                        color = NostrordColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "online",
                        color = NostrordColors.Success,
                        fontSize = 11.sp,
                    )
                }
                Icon(
                    imageVector = if (menuOpen) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                    contentDescription = "Switch account",
                    tint = NostrordColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Settings",
                    tint = NostrordColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/** Which step of the rail "+" add-group flow is open. */
private enum class AddGroupStep { CHOOSER, CREATE, JOIN }

/** Short signer label for the account chip (prototype AccountMenu). */
private fun signerLabel(method: AuthMethod): String = when (method) {
    AuthMethod.LOCAL -> "key"
    AuthMethod.BUNKER -> "bunker"
    AuthMethod.NIP07 -> "extension"
}
