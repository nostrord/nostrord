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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.auth.removeAccountBusyLabel
import org.nostr.nostrord.auth.removeAccountConfirmLabel
import org.nostr.nostrord.auth.removeAccountDialogBody
import org.nostr.nostrord.auth.removeAccountDialogTitle
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.forms.AppSegmentedTabs
import org.nostr.nostrord.ui.components.forms.SegmentedTab
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.navigation.HashRoute
import org.nostr.nostrord.ui.navigation.LocalFrameNavigator
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.dm.DmPageScreen
import org.nostr.nostrord.ui.screens.group.GroupScreen
import org.nostr.nostrord.ui.screens.group.components.AddGroupModal
import org.nostr.nostrord.ui.screens.group.components.CreateGroupModal
import org.nostr.nostrord.ui.screens.group.components.JoinGroupModal
import org.nostr.nostrord.ui.screens.home.HomePageScreen
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.ui.screens.profile.ProfilePageScreen
import org.nostr.nostrord.ui.screens.settings.SettingsScreen
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.utils.normalizeRelayUrl

/**
 * New-design logged-in frame (prototype AppShell): the 72px groups rail (home,
 * joined groups with unread badges, add, DMs and notifications) plus the 240px
 * sidebar (home hub or the open group's sidebar, with the account bar) around the
 * content. Owns the new-design navigation: home vs an open group ([GroupRoute]),
 * mirroring the web's #/g/<relay>/<groupId> hash. Compact widths (< 600dp) show
 * the content alone; the mobile drawer comes later.
 */
@Composable
fun AppFrame() {
    val vm = viewModel { HomePageViewModel(AppModule.nostrRepository, AppModule.notificationHistoryStore) }
    val groups by vm.myGroups.collectAsState()
    val unreadCounts by vm.unreadCounts.collectAsState()
    val notificationUnread by vm.notificationUnread.collectAsState()
    var showSettings by remember { mutableStateOf(false) }
    var addGroupStep by remember { mutableStateOf<AddGroupStep?>(null) }
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

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth < 600.dp) {
            FrameContent(
                route = route,
                forceDesktop = false,
                onNavigate = { route = it },
                onCloseGroup = { route = null },
                onConsumeInvite = { route = (route as? GroupRoute)?.copy(inviteCode = null) },
                onEditProfile = { showSettings = true },
            )
        } else {
            Row(modifier = Modifier.fillMaxSize()) {
                // ── Groups rail ──────────────────────────────────────────────
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
                        RailButton(icon = Icons.Default.Home, label = "Home", active = route == null) {
                            route = null
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
                                    active = groupRoute?.groupId == group.meta.id,
                                ) { route = GroupRoute(group.relayUrl, group.meta.id) }
                            }
                        }
                        RailButton(icon = Icons.Default.Add, label = "Add group") {
                            addGroupStep = AddGroupStep.CHOOSER
                        }
                    }
                    HorizontalDivider(modifier = Modifier.width(32.dp), color = NostrordColors.Divider)
                    RailButton(icon = Icons.Default.Mail, label = "Direct messages", active = route is DmRoute) {
                        route = DmRoute()
                    }
                    Box {
                        RailButton(icon = Icons.Default.Notifications, label = "Notifications") { /* not ported yet */ }
                        if (notificationUnread > 0) {
                            RailBadge(
                                count = notificationUnread,
                                modifier = Modifier.align(Alignment.TopEnd),
                            )
                        }
                    }
                }

                // ── Sidebar: home hub or the open group's tree ───────────────
                Column(
                    modifier =
                    Modifier
                        .width(240.dp)
                        .fillMaxHeight()
                        .background(NostrordColors.Surface),
                ) {
                    if (groupRoute != null) {
                        Box(modifier = Modifier.weight(1f)) {
                            GroupSidebar(route = groupRoute, onNavigateGroup = { route = it })
                        }
                    } else if (route is DmRoute) {
                        Box(modifier = Modifier.weight(1f)) {
                            DmSidebar(onOpenConversation = { route = it })
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
                        HomeHub(modifier = Modifier.weight(1f))
                    }
                    AccountBar(onOpenSettings = { showSettings = true })
                }

                // ── Content ──────────────────────────────────────────────────
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    FrameContent(
                        route = route,
                        forceDesktop = true,
                        onNavigate = { route = it },
                        onCloseGroup = { route = null },
                        onConsumeInvite = { route = (route as? GroupRoute)?.copy(inviteCode = null) },
                        onEditProfile = { showSettings = true },
                    )
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
                    onGroupCreated = { groupId, _ ->
                        addGroupStep = null
                        route = GroupRoute(currentRelayUrl, groupId)
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
    }
}

/** Home page, the open group's chat (the legacy GroupScreen, full-featured) or a profile. */
@Composable
private fun FrameContent(
    route: HashRoute?,
    forceDesktop: Boolean,
    onNavigate: (HashRoute) -> Unit,
    onCloseGroup: () -> Unit,
    onConsumeInvite: () -> Unit,
    onEditProfile: () -> Unit,
) {
    CompositionLocalProvider(LocalFrameNavigator provides onNavigate) {
        when (route) {
            null -> HomePageScreen(onOpenGroup = { onNavigate(GroupRoute(it.relayUrl, it.meta.id)) })
            is UserRoute ->
                ProfilePageScreen(
                    pubkey = route.pubkey,
                    onOpenGroup = onNavigate,
                    onEditProfile = onEditProfile,
                )
            is DmRoute -> DmPageScreen(pubkey = route.pubkey, onOpenProfile = onNavigate)
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
private fun HomeHub(modifier: Modifier = Modifier) {
    var hub by remember { mutableStateOf(0) }
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
        // Layout-only hub: friends/saved data arrives with the follow logic.
        Text(
            if (hub == 0) "You don't follow anyone yet." else "Nothing saved yet.",
            color = NostrordColors.TextMuted,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun AccountBar(onOpenSettings: () -> Unit) {
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
            DropdownMenuItem(
                onClick = {
                    menuOpen = false /* add-account sheet: not wired in the new flow yet */
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
