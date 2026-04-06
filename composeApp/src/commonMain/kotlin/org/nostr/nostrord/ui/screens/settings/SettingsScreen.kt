package org.nostr.nostrord.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.network.outbox.RelayListManager
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.cards.InfoCard
import org.nostr.nostrord.ui.components.upload.UploadImageField
import org.nostr.nostrord.ui.components.cards.KeyCard
import org.nostr.nostrord.ui.components.cards.WarningCard
import org.nostr.nostrord.ui.components.navigation.NavigationToolbar
import org.nostr.nostrord.ui.screens.profile.EditProfileViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.rememberClipboardWriter

enum class SettingsSection(val label: String) {
    Profile("Profile"),
    BackupKeys("Backup Keys"),
    RelaysNip65("Relays (NIP-65)")
}

/**
 * Full-screen settings overlay.
 *
 * Desktop layout (matches new-user.html .settings-overlay):
 * ┌──────────────────┬───────────────────────────────────┬──────────┐
 * │  Sidebar 218dp   │  Content (scrollable, max 660dp)  │  80dp    │
 * │  BackgroundDark  │  Background                       │  Close   │
 * └──────────────────┴───────────────────────────────────┴──────────┘
 *
 * Mobile: nav list full-screen → tap item → panel with back button.
 * ESC key closes the overlay.
 */
@Composable
fun SettingsScreen(
    onClose: () -> Unit,
    onNavigate: (Screen) -> Unit,
    onLogout: () -> Unit,
    showToolbar: Boolean = false,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    onHistoryBack: () -> Unit = {},
    onHistoryForward: () -> Unit = {}
) {
    val vm = viewModel { EditProfileViewModel(AppModule.nostrRepository) }
    val userMetadata by vm.userMetadata.collectAsState()
    val publicKey = vm.getPublicKey()
    val currentUserMetadata = publicKey?.let { userMetadata[it] }

    // Form state — reinitialised when metadata loads
    // Single "Name" field populates both display_name and name in the event
    var name by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.displayName ?: currentUserMetadata?.name ?: "")
    }
    var about by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.about ?: "")
    }
    var pictureUrl by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.picture ?: "")
    }
    var bannerUrl by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.banner ?: "")
    }
    var nip05 by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.nip05 ?: "")
    }
    var lightningAddress by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.lud16 ?: "")
    }
    var website by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.website ?: "")
    }
    var isSaving by remember { mutableStateOf(false) }
    var showSuccessMessage by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showSuccessMessage) {
        if (showSuccessMessage) { delay(2000); showSuccessMessage = false }
    }
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) { delay(3000); errorMessage = null }
    }

    val onSave: () -> Unit = {
        isSaving = true
        errorMessage = null
        val nameValue = name.ifBlank { null }
        vm.saveProfile(
            displayName = nameValue,
            name = nameValue,
            about = about.ifBlank { null },
            picture = pictureUrl.ifBlank { null },
            banner = bannerUrl.ifBlank { null },
            nip05 = nip05.ifBlank { null },
            lud16 = lightningAddress.ifBlank { null },
            website = website.ifBlank { null }
        ) { result ->
            isSaving = false
            if (result.isSuccess) showSuccessMessage = true
            else errorMessage = result.exceptionOrNull()?.message ?: "Failed to update profile"
        }
    }

    val profileContent: @Composable () -> Unit = {
        ProfileFormContent(
            name = name,
            about = about,
            pictureUrl = pictureUrl,
            bannerUrl = bannerUrl,
            nip05 = nip05,
            lightningAddress = lightningAddress,
            website = website,
            pubkey = publicKey,
            isSaving = isSaving,
            showSuccessMessage = showSuccessMessage,
            errorMessage = errorMessage,
            onNameChange = { name = it },
            onAboutChange = { about = it },
            onPictureUrlChange = { pictureUrl = it },
            onBannerUrlChange = { bannerUrl = it },
            onNip05Change = { nip05 = it },
            onLightningAddressChange = { lightningAddress = it },
            onWebsiteChange = { website = it },
            onSave = onSave
        )
    }

    // Backup state
    val copyToClipboard = rememberClipboardWriter()
    val privateKey = remember { AppModule.nostrRepository.getPrivateKey() }
    var showKeyCopied by remember { mutableStateOf(false) }
    LaunchedEffect(showKeyCopied) {
        if (showKeyCopied) { delay(2000); showKeyCopied = false }
    }

    val backupContent: @Composable () -> Unit = {
        BackupPanelContent(
            privateKey = privateKey,
            publicKey = publicKey,
            showCopiedMessage = showKeyCopied,
            onCopyPublicKey = { publicKey?.let { copyToClipboard(it); showKeyCopied = true } },
            onCopyPrivateKey = { privateKey?.let { copyToClipboard(it); showKeyCopied = true } }
        )
    }

    // NIP-65 relay state
    val userRelayList by AppModule.nostrRepository.userRelayList.collectAsState()
    val usingDefaultRelays = userRelayList.isEmpty()
    val effectiveRelayList = if (usingDefaultRelays) {
        RelayListManager.DEFAULT_FALLBACK_RELAYS.map { Nip65Relay(it) }
    } else {
        userRelayList
    }
    val relaysContent: @Composable () -> Unit = {
        RelayNip65PanelContent(
            currentRelays = effectiveRelayList,
            usingDefaults = usingDefaultRelays,
            onPublish = { relays -> AppModule.nostrRepository.publishRelayList(relays) }
        )
    }

    var activeSection by remember { mutableStateOf(SettingsSection.Profile) }
    var showMobilePanel by remember { mutableStateOf(false) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NostrordColors.Background)
            // Consume all pointer events so the chat behind is not interactive
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { /* consume clicks */ }
            .focusRequester(focusRequester)
            .focusTarget()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onClose(); true
                } else false
            }
    ) {
        if (maxWidth < 600.dp) {
            MobileSettings(
                activeSection = activeSection,
                showPanel = showMobilePanel,
                onSelectSection = { section -> activeSection = section; showMobilePanel = true },
                onBack = { showMobilePanel = false },
                onClose = onClose,
                onLogout = onLogout,
                profileContent = profileContent,
                backupContent = backupContent,
                relaysContent = relaysContent
            )
        } else {
            DesktopSettings(
                activeSection = activeSection,
                onSelectSection = { activeSection = it },
                onClose = onClose,
                onLogout = onLogout,
                profileContent = profileContent,
                backupContent = backupContent,
                relaysContent = relaysContent,
                showToolbar = showToolbar,
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                onHistoryBack = onHistoryBack,
                onHistoryForward = onHistoryForward
            )
        }
    }
}

// ── Desktop ──────────────────────────────────────────────────────────────────

@Composable
private fun DesktopSettings(
    activeSection: SettingsSection,
    onSelectSection: (SettingsSection) -> Unit,
    onClose: () -> Unit,
    onLogout: () -> Unit,
    profileContent: @Composable () -> Unit,
    backupContent: @Composable () -> Unit,
    relaysContent: @Composable () -> Unit,
    showToolbar: Boolean = false,
    canGoBack: Boolean = false,
    canGoForward: Boolean = false,
    onHistoryBack: () -> Unit = {},
    onHistoryForward: () -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxSize()) {
        if (showToolbar) {
            NavigationToolbar(
                canGoBack = canGoBack,
                canGoForward = canGoForward,
                onBack = onHistoryBack,
                onForward = onHistoryForward
            )
        }

        // Centered layout: sidebar(218) + content(740) + close(80) = 1038dp center block.
        // Two equal fills extend the sidebar/content background colors to the screen edges,
        // mirroring the CSS: settings-sidebar-fill { flex:1 } / settings-content-fill { flex:1 }.
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val sidebarW = 218.dp
            val closeW = 80.dp
            val contentMaxW = 740.dp
            // On narrow screens shrink content rather than overflow
            val centerW = (sidebarW + contentMaxW + closeW).coerceAtMost(maxWidth)
            val contentW = (centerW - sidebarW - closeW).coerceAtLeast(0.dp)
            val fillW = ((maxWidth - centerW) / 2f).coerceAtLeast(0.dp)

            // Background layer: sidebar color bleeds to left edge, content color bleeds right
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.width(fillW + sidebarW).fillMaxHeight().background(NostrordColors.BackgroundDark))
                Box(Modifier.weight(1f).fillMaxHeight().background(NostrordColors.Background))
            }

            // Content layer: left spacer positions sidebar at the correct offset
            Row(Modifier.fillMaxSize()) {
                Spacer(Modifier.width(fillW))

                Column(
                    modifier = Modifier
                        .width(sidebarW)
                        .fillMaxHeight()
                        .padding(top = 24.dp, end = 8.dp, bottom = 24.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    SettingsSidebar(
                        activeSection = activeSection,
                        onSelectSection = onSelectSection,
                        onLogout = onLogout
                    )
                }

                Column(
                    modifier = Modifier
                        .width(contentW)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 24.dp, start = 40.dp, end = 20.dp, bottom = 80.dp)
                ) {
                    Box(modifier = Modifier.widthIn(max = 660.dp)) {
                        SettingsPanel(activeSection, profileContent, backupContent, relaysContent)
                    }
                }

                Column(modifier = Modifier.width(closeW).padding(top = 24.dp, start = 16.dp)) {
                    SettingsCloseButton(onClick = onClose)
                }
            }
        }
    }
}

// ── Mobile ───────────────────────────────────────────────────────────────────

@Composable
private fun MobileSettings(
    activeSection: SettingsSection,
    showPanel: Boolean,
    onSelectSection: (SettingsSection) -> Unit,
    onBack: () -> Unit,
    onClose: () -> Unit,
    onLogout: () -> Unit,
    profileContent: @Composable () -> Unit,
    backupContent: @Composable () -> Unit,
    relaysContent: @Composable () -> Unit
) {
    if (!showPanel) {
        Column(modifier = Modifier.fillMaxSize().background(NostrordColors.BackgroundDark)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings", color = NostrordColors.TextPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                SettingsCloseButton(onClick = onClose, showEscHint = false)
            }
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                SettingsSidebar(
                    activeSection = activeSection,
                    onSelectSection = onSelectSection,
                    onLogout = onLogout,
                    compact = true
                )
            }
        }
    } else {
        Column(modifier = Modifier.fillMaxSize().background(NostrordColors.Background)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(NostrordColors.BackgroundDark)
                    .clickable(onClick = onBack)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = NostrordColors.TextSecondary, fontSize = 22.sp)
                Spacer(Modifier.width(8.dp))
                Text("Settings", color = NostrordColors.TextSecondary, fontSize = 14.sp)
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                SettingsPanel(activeSection, profileContent, backupContent, relaysContent)
            }
        }
    }
}

// ── Shared sidebar ────────────────────────────────────────────────────────────

@Composable
private fun SettingsSidebar(
    activeSection: SettingsSection,
    onSelectSection: (SettingsSection) -> Unit,
    onLogout: () -> Unit,
    compact: Boolean = false
) {
    var showLogoutConfirm by remember { mutableStateOf(false) }

    if (showLogoutConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showLogoutConfirm = false },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Log out?") },
            text = { Text("You will need your private key or bunker URL to log back in.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showLogoutConfirm = false
                    onLogout()
                }) {
                    Text("Log Out", color = NostrordColors.Error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showLogoutConfirm = false }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            }
        )
    }

    SettingsNavItem("Profile", activeSection == SettingsSection.Profile, compact = compact) {
        onSelectSection(SettingsSection.Profile)
    }
    SettingsNavItem("Backup Keys", activeSection == SettingsSection.BackupKeys, compact = compact) {
        onSelectSection(SettingsSection.BackupKeys)
    }
    SettingsNavItem("Relays (NIP-65)", activeSection == SettingsSection.RelaysNip65, compact = compact) {
        onSelectSection(SettingsSection.RelaysNip65)
    }
    SettingsNavDivider(compact)
    SettingsNavItem("Log Out", isActive = false, isDanger = true, compact = compact,
        onClick = { showLogoutConfirm = true })
}

// ── Nav primitives ────────────────────────────────────────────────────────────

@Composable
private fun SettingsNavItem(
    label: String,
    isActive: Boolean,
    isDanger: Boolean = false,
    compact: Boolean = false,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val textColor = when {
        isDanger -> NostrordColors.Error
        isActive || isHovered -> NostrordColors.TextPrimary
        else -> NostrordColors.TextSecondary
    }

    if (compact) {
        val bg = if (isDanger && isHovered) NostrordColors.Error.copy(alpha = 0.1f) else NostrordColors.Background
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().background(bg)
                    .hoverable(interactionSource).clickable(onClick = onClick)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = label, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                if (!isDanger) Text("›", color = NostrordColors.TextMuted, fontSize = 20.sp, lineHeight = 20.sp)
            }
            HorizontalDivider(color = NostrordColors.Divider)
        }
    } else {
        val bg = when {
            isDanger && isHovered -> NostrordColors.Error.copy(alpha = 0.15f)
            isActive || isHovered -> NostrordColors.HoverBackground
            else -> Color.Transparent
        }
        Box(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)).background(bg)
                .hoverable(interactionSource).clickable(onClick = onClick)
                .pointerHoverIcon(PointerIcon.Hand).padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                color = textColor,
                fontSize = 14.sp,
                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Medium
            )
        }
    }
}

@Composable
private fun SettingsNavDivider(compact: Boolean = false) {
    HorizontalDivider(
        modifier = if (compact) Modifier else Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
        color = NostrordColors.Divider
    )
}

// ── Close button ─────────────────────────────────────────────────────────────

@Composable
private fun SettingsCloseButton(onClick: () -> Unit, showEscHint: Boolean = true) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Column(
        modifier = Modifier.size(40.dp).clip(CircleShape)
            .background(if (isHovered) NostrordColors.HoverBackground else NostrordColors.SurfaceVariant)
            .hoverable(interactionSource).clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Close, contentDescription = "Close settings",
            tint = NostrordColors.TextSecondary, modifier = Modifier.size(16.dp))
        if (showEscHint) {
            Text("ESC", color = NostrordColors.TextMuted, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
    }
}

// ── Content panels ────────────────────────────────────────────────────────────

@Composable
private fun SettingsPanel(
    section: SettingsSection,
    profileContent: @Composable () -> Unit,
    backupContent: @Composable () -> Unit,
    relaysContent: @Composable () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = section.label,
            color = NostrordColors.TextPrimary,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp)
        )
        HorizontalDivider(color = NostrordColors.Divider)
        Spacer(Modifier.height(24.dp))

        when (section) {
            SettingsSection.Profile    -> profileContent()
            SettingsSection.BackupKeys -> backupContent()
            SettingsSection.RelaysNip65 -> relaysContent()
        }
    }
}

// ── Profile form panel ────────────────────────────────────────────────────────

@Composable
private fun ProfileFormContent(
    name: String,
    about: String,
    pictureUrl: String,
    bannerUrl: String,
    nip05: String,
    lightningAddress: String,
    website: String,
    pubkey: String?,
    isSaving: Boolean,
    showSuccessMessage: Boolean,
    errorMessage: String?,
    onNameChange: (String) -> Unit,
    onAboutChange: (String) -> Unit,
    onPictureUrlChange: (String) -> Unit,
    onBannerUrlChange: (String) -> Unit,
    onNip05Change: (String) -> Unit,
    onLightningAddressChange: (String) -> Unit,
    onWebsiteChange: (String) -> Unit,
    onSave: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        // Avatar preview card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                ProfileAvatar(
                    imageUrl = pictureUrl.ifBlank { null },
                    displayName = name.ifBlank { "User" },
                    pubkey = pubkey ?: "",
                    size = 100.dp
                )
                Spacer(Modifier.height(Spacing.sm))
                Text("Avatar Preview", style = NostrordTypography.Caption, color = NostrordColors.TextMuted)
            }
        }

        // Form card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.lg)
            ) {
                Text(
                    text = "PROFILE INFORMATION",
                    style = NostrordTypography.SectionHeader,
                    color = NostrordColors.TextMuted
                )

                ProfileField("Name", name, onNameChange, "Your name")
                ProfileField("About", about, onAboutChange, "Tell us about yourself", singleLine = false, maxLines = 4)
                UploadImageField(
                    label = "Avatar URL",
                    value = pictureUrl,
                    onValueChange = onPictureUrlChange,
                    placeholder = "https://example.com/avatar.jpg"
                )
                UploadImageField(
                    label = "Banner URL",
                    value = bannerUrl,
                    onValueChange = onBannerUrlChange,
                    placeholder = "https://example.com/banner.jpg"
                )
                ProfileField("Nostr Address (NIP-05)", nip05, onNip05Change, "you@example.com")
                ProfileField("Lightning Address", lightningAddress, onLightningAddressChange, "you@walletofsatoshi.com")
                ProfileField("Website", website, onWebsiteChange, "https://example.com")

                // Save button
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onSave, enabled = !isSaving) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = NostrordColors.Primary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save", color = NostrordColors.Primary, style = NostrordTypography.Button)
                        }
                    }
                }
            }
        }

        // Inline feedback
        when {
            showSuccessMessage -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NostrordColors.Success),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Check, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Profile updated successfully", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
            errorMessage != null -> {
                Card(
                    colors = CardDefaults.cardColors(containerColor = NostrordColors.Error),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Error, contentDescription = null,
                            tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(errorMessage, color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    singleLine: Boolean = true,
    maxLines: Int = 1
) {
    Column {
        Text(text = label, style = NostrordTypography.SectionHeader, color = NostrordColors.TextMuted)
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(placeholder, color = NostrordColors.TextMuted) },
            singleLine = singleLine,
            maxLines = maxLines,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NostrordColors.Primary,
                unfocusedBorderColor = NostrordColors.Divider,
                focusedContainerColor = NostrordColors.InputBackground,
                unfocusedContainerColor = NostrordColors.InputBackground,
                cursorColor = NostrordColors.Primary,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            shape = NostrordShapes.shapeSmall
        )
    }
}

// ── Backup panel content ──────────────────────────────────────────────────────

@Composable
private fun BackupPanelContent(
    privateKey: String?,
    publicKey: String?,
    showCopiedMessage: Boolean,
    onCopyPublicKey: () -> Unit,
    onCopyPrivateKey: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg)
    ) {
        Icon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = NostrordColors.WarningOrange,
            modifier = Modifier.size(48.dp)
        )

        WarningCard(isCompact = false)

        if (publicKey != null) {
            KeyCard(
                title = "Public Key (npub)",
                titleColor = NostrordColors.TextSecondary,
                keyValue = publicKey,
                keyColor = Color.White,
                buttonText = "Copy Public Key",
                buttonColor = NostrordColors.Primary,
                onCopy = onCopyPublicKey,
                isCompact = false
            )
        }

        if (privateKey != null) {
            KeyCard(
                title = "Private Key (nsec)",
                titleColor = NostrordColors.Error,
                keyValue = privateKey,
                keyColor = NostrordColors.LightRed,
                buttonText = "Copy Private Key",
                buttonColor = NostrordColors.Error,
                onCopy = onCopyPrivateKey,
                isCompact = false,
                showSecretBadge = true
            )
        }

        if (showCopiedMessage) {
            Card(
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Success),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Check, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Copied to clipboard", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }

        InfoCard(
            title = "Security Tips",
            titleColor = NostrordColors.Warning,
            icon = Icons.Default.Lightbulb,
            content = "1. Write it down on paper and store in a safe place\n" +
                    "2. Use a password manager like 1Password or Bitwarden\n" +
                    "3. Never store it in plain text files or screenshots\n" +
                    "4. Never send it via email or messaging apps\n" +
                    "5. Consider using a hardware wallet for long-term storage",
            isCompact = false
        )
    }
}
