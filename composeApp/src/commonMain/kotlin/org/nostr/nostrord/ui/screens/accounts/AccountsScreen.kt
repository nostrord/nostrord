package org.nostr.nostrord.ui.screens.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.badges.UnreadBadge
import org.nostr.nostrord.ui.theme.NostrordColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onNavigate: (Screen) -> Unit) {
    val accountStore = AppModule.accountStore
    val accountManager = AppModule.accountManager
    val accounts by accountStore.accounts.collectAsState()
    val activeId by accountStore.activeId.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    // Drives recomposition of per-row unread badges.
    val activeNotifications by AppModule.notificationHistoryStore.entries.collectAsState()
    val scope = rememberCoroutineScope()

    var renameTarget by remember { mutableStateOf<Account?>(null) }
    var removeTarget by remember { mutableStateOf<Account?>(null) }
    var pendingError by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

    // Fetch kind:0 for any account whose metadata isn't cached yet, so each
    // row can show the avatar / display name set on the user's profile.
    LaunchedEffect(accounts) {
        val missing = accounts.map { it.pubkey }.filter { it !in userMetadata.keys }.toSet()
        if (missing.isNotEmpty()) {
            AppModule.nostrRepository.requestUserMetadata(missing)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Accounts", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = { onNavigate(Screen.Home) }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NostrordColors.Surface,
                ),
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onNavigate(Screen.NostrLogin) },
                containerColor = NostrordColors.Primary,
                icon = { Icon(Icons.Default.Add, contentDescription = null, tint = Color.White) },
                text = { Text("Add account", color = Color.White) },
            )
        },
        containerColor = NostrordColors.Background,
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            pendingError?.let { msg ->
                Text(
                    msg,
                    color = NostrordColors.Error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
            if (accounts.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No accounts yet",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            } else {
                Text(
                    "Adding an account opens the login screen. Local key, bunker, " +
                        "and browser extension all work; the new identity becomes active.",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(accounts, key = { it.id }) { account ->
                        val unread = remember(account.pubkey, activeId, activeNotifications) {
                            AppModule.notificationHistoryStore.unreadCountFor(account.pubkey)
                        }
                        AccountRow(
                            account = account,
                            metadata = userMetadata[account.pubkey],
                            isActive = account.id == activeId,
                            isBusy = isBusy,
                            unreadCount = unread,
                            onClick = {
                                if (account.id == activeId || isBusy) return@AccountRow
                                isBusy = true
                                pendingError = null
                                scope.launch {
                                    val r = accountManager.switchAccount(account.id)
                                    isBusy = false
                                    if (r.isFailure) {
                                        pendingError = r.exceptionOrNull()?.message
                                            ?: "Switch failed"
                                    } else {
                                        onNavigate(Screen.Home)
                                    }
                                }
                            },
                            onRename = { renameTarget = account },
                            onRemove = {
                                if (isBusy) return@AccountRow
                                removeTarget = account
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        RenameAccountDialog(
            current = target,
            onDismiss = { renameTarget = null },
            onConfirm = { newLabel ->
                accountStore.rename(target.id, newLabel)
                renameTarget = null
            },
        )
    }

    removeTarget?.let { target ->
        val isActiveTarget = target.id == activeId
        // The same most-recently-added fallback that AccountManager will pick.
        // Computed here purely for the dialog copy so the user knows where
        // they'll land before confirming.
        val fallback = if (isActiveTarget) {
            accounts.filter { it.id != target.id }.maxByOrNull { it.addedAt }
        } else null
        val fallbackLabel = fallback?.let { fb ->
            userMetadata[fb.pubkey]?.displayName?.takeIf { it.isNotBlank() }
                ?: userMetadata[fb.pubkey]?.name?.takeIf { it.isNotBlank() }
                ?: fb.label
        }
        RemoveAccountDialog(
            account = target,
            isActive = isActiveTarget,
            fallbackLabel = fallbackLabel,
            isBusy = isBusy,
            onDismiss = { if (!isBusy) removeTarget = null },
            onConfirm = {
                if (isBusy) return@RemoveAccountDialog
                isBusy = true
                scope.launch {
                    accountManager.removeAccount(target.id)
                    isBusy = false
                    removeTarget = null
                }
            },
        )
    }
}

@Composable
private fun AccountRow(
    account: Account,
    metadata: UserMetadata?,
    isActive: Boolean,
    isBusy: Boolean,
    unreadCount: Int,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onRemove: () -> Unit,
) {
    val npub = remember(account.pubkey) {
        try {
            Nip19.encodeNpub(account.pubkey)
        } catch (_: Exception) {
            account.pubkey
        }
    }
    // Prefer the user's kind:0 profile fields, fall back to the stored label
    // (which is the generic "Account N" until the user renames it).
    val profileName = metadata?.displayName?.takeIf { it.isNotBlank() }
        ?: metadata?.name?.takeIf { it.isNotBlank() }
    val primaryLabel = profileName ?: account.label
    val secondaryLabel = if (profileName != null && account.label.startsWith("Account ").not()) {
        account.label  // user-renamed label as a subtitle when both exist
    } else {
        null
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NostrordColors.Surface)
            .clickable(enabled = !isBusy && !isActive, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ProfileAvatar(
                imageUrl = metadata?.picture?.takeIf { it.isNotBlank() },
                displayName = profileName ?: account.label,
                pubkey = account.pubkey,
                size = 40.dp,
            )
            if (unreadCount > 0) {
                UnreadBadge(
                    count = unreadCount,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 6.dp, y = (-4).dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    primaryLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (isActive) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Default.Check,
                        contentDescription = "Active",
                        tint = NostrordColors.Primary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
            if (secondaryLabel != null) {
                Text(
                    secondaryLabel,
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                npub.take(16) + "…",
                color = NostrordColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                account.authMethod.label(),
                color = NostrordColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
            )
        }
        IconButton(onClick = onRename, enabled = !isBusy) {
            Icon(Icons.Default.Edit, contentDescription = "Rename", tint = NostrordColors.TextSecondary)
        }
        IconButton(onClick = onRemove, enabled = !isBusy) {
            Icon(Icons.Default.Delete, contentDescription = "Remove", tint = NostrordColors.Error)
        }
    }
}

@Composable
private fun RenameAccountDialog(
    current: Account,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var label by remember { mutableStateOf(current.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NostrordColors.Surface,
        title = { Text("Rename account", color = Color.White) },
        text = {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(label.trim().ifBlank { current.label }) },
            ) {
                Text("Save", color = NostrordColors.Primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NostrordColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun RemoveAccountDialog(
    account: Account,
    isActive: Boolean,
    fallbackLabel: String?,
    isBusy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    // Body varies by whether this is the active account and whether a fallback
    // identity exists.
    val body = when {
        isActive && fallbackLabel != null ->
            "Credentials and local data for \"${account.label}\" will be erased on " +
                "this device. You'll switch to \"$fallbackLabel\"."
        isActive ->
            "Credentials and local data for \"${account.label}\" will be erased on " +
                "this device. You'll be signed out."
        else ->
            "Credentials and local data for \"${account.label}\" will be erased on " +
                "this device."
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NostrordColors.Surface,
        titleContentColor = Color.White,
        textContentColor = NostrordColors.TextSecondary,
        title = { Text("Remove account?") },
        text = { Text(body) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isBusy,
            ) {
                Text(
                    if (isBusy) "Removing…" else "Remove",
                    color = NostrordColors.Error,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !isBusy) {
                Text("Cancel", color = NostrordColors.TextSecondary)
            }
        },
    )
}

private fun AuthMethod.label(): String = when (this) {
    AuthMethod.LOCAL -> "Local key"
    AuthMethod.BUNKER -> "Bunker (NIP-46)"
    AuthMethod.NIP07 -> "Extension (NIP-07)"
    AuthMethod.READ_ONLY -> "Read-only"
    AuthMethod.GUEST -> "Guest"
}
