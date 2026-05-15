package org.nostr.nostrord.ui.screens.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountsScreen(onNavigate: (Screen) -> Unit) {
    val accountStore = AppModule.accountStore
    val accountManager = AppModule.accountManager
    val accounts by accountStore.accounts.collectAsState()
    val activeId by accountStore.activeId.collectAsState()
    val scope = rememberCoroutineScope()

    var showAddDialog by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Account?>(null) }
    var pendingError by remember { mutableStateOf<String?>(null) }
    var isBusy by remember { mutableStateOf(false) }

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
            FloatingActionButton(
                onClick = { showAddDialog = true },
                containerColor = NostrordColors.Primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add account", tint = Color.White)
            }
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
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                ) {
                    items(accounts, key = { it.id }) { account ->
                        AccountRow(
                            account = account,
                            isActive = account.id == activeId,
                            isBusy = isBusy,
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
                                isBusy = true
                                scope.launch {
                                    accountManager.removeAccount(account.id)
                                    isBusy = false
                                }
                            },
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddAccountDialog(
            existingPubkeys = accounts.map { it.pubkey }.toSet(),
            onDismiss = { showAddDialog = false },
            onConfirm = { privateKeyHex, label ->
                isBusy = true
                pendingError = null
                val r = accountManager.addLocalAccount(privateKeyHex, label)
                isBusy = false
                if (r.isFailure) {
                    pendingError = r.exceptionOrNull()?.message ?: "Could not add account"
                } else {
                    showAddDialog = false
                }
            },
        )
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
}

@Composable
private fun AccountRow(
    account: Account,
    isActive: Boolean,
    isBusy: Boolean,
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(NostrordColors.Surface)
            .clickable(enabled = !isBusy && !isActive, onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProfileAvatar(
            imageUrl = null,
            displayName = account.label,
            pubkey = account.pubkey,
            size = 40.dp,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    account.label,
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
private fun AddAccountDialog(
    existingPubkeys: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (privateKeyHex: String, label: String?) -> Unit,
) {
    var privateKey by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showKey by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NostrordColors.Surface,
        title = { Text("Add account", color = Color.White) },
        text = {
            Column {
                Text(
                    "Paste a private key (hex or nsec). Bunker and NIP-07 accounts " +
                        "show up here after you log in with them on the login screen.",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = privateKey,
                    onValueChange = { privateKey = it; error = null },
                    singleLine = true,
                    placeholder = { Text("nsec1… or hex", color = NostrordColors.TextMuted) },
                    visualTransformation = if (showKey) VisualTransformation.None
                        else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showKey = !showKey }) {
                            Text(if (showKey) "Hide" else "Show",
                                color = NostrordColors.TextSecondary,
                                style = MaterialTheme.typography.labelSmall)
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    singleLine = true,
                    placeholder = { Text("Label (optional)", color = NostrordColors.TextMuted) },
                    modifier = Modifier.fillMaxWidth(),
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = NostrordColors.Error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val input = privateKey.trim()
                if (input.isBlank()) {
                    error = "Enter a private key"
                    return@TextButton
                }
                val hex = if (input.startsWith("nsec1")) {
                    (Nip19.decode(input) as? Nip19.Entity.Nsec)?.privkey
                } else input
                if (hex.isNullOrBlank()) {
                    error = "Invalid private key"
                    return@TextButton
                }
                val pubkey = try {
                    KeyPair.fromPrivateKeyHex(hex).publicKeyHex
                } catch (_: Exception) {
                    error = "Invalid private key"
                    return@TextButton
                }
                if (pubkey in existingPubkeys) {
                    error = "Account already exists for this key"
                    return@TextButton
                }
                onConfirm(hex, label.trim().ifBlank { null })
            }) {
                Text("Add", color = NostrordColors.Primary)
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

private fun AuthMethod.label(): String = when (this) {
    AuthMethod.LOCAL -> "Local key"
    AuthMethod.BUNKER -> "Bunker (NIP-46)"
    AuthMethod.NIP07 -> "Extension (NIP-07)"
}
