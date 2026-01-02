package org.nostr.nostrord.ui.screens.relay

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.screens.relay.model.RelayInfo
import org.nostr.nostrord.ui.screens.relay.model.RelayStatus
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun RelaySettingsScreen(
    listState: LazyListState = rememberLazyListState(),
    onNavigate: (Screen) -> Unit
) {
    val scope = rememberCoroutineScope()
    val currentRelay by NostrRepository.currentRelayUrl.collectAsState()

    var relays by remember {
        mutableStateOf(
            listOf(
                RelayInfo("wss://groups.fiatjaf.com"),
                RelayInfo("wss://relay.groups.nip29.com"),
                RelayInfo("wss://groups.0xchat.com"),
                RelayInfo("wss://groups.hzrd149.com"),
                RelayInfo("wss://pyramid.fiatjaf.com")
            )
        )
    }

    var newRelayUrl by remember { mutableStateOf("") }
    var showAddDialog by remember { mutableStateOf(false) }

    LaunchedEffect(currentRelay) {
        relays = relays.map { relay ->
            relay.copy(
                status = if (relay.url == currentRelay) RelayStatus.CONNECTED else RelayStatus.DISCONNECTED
            )
        }
    }

    // Add Relay Dialog
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Add New Relay") },
            text = {
                Column {
                    Text("Enter the WebSocket URL of the relay:")
                    Spacer(modifier = Modifier.height(8.dp))
                    TextField(
                        value = newRelayUrl,
                        onValueChange = { newRelayUrl = it },
                        placeholder = { Text("wss://example.com") },
                        modifier = Modifier.fillMaxWidth(),
                        colors = TextFieldDefaults.colors(
                            focusedTextColor = NostrordColors.TextPrimary,
                            unfocusedTextColor = NostrordColors.TextPrimary,
                            focusedContainerColor = NostrordColors.InputBackground,
                            unfocusedContainerColor = NostrordColors.InputBackground,
                            focusedPlaceholderColor = NostrordColors.TextMuted,
                            unfocusedPlaceholderColor = NostrordColors.TextMuted,
                            cursorColor = NostrordColors.Primary,
                            focusedIndicatorColor = NostrordColors.Primary,
                            unfocusedIndicatorColor = NostrordColors.Divider
                        )
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newRelayUrl.isNotBlank() && newRelayUrl.startsWith("wss://")) {
                            relays = relays + RelayInfo(newRelayUrl)
                            newRelayUrl = ""
                            showAddDialog = false
                        }
                    },
                    enabled = newRelayUrl.isNotBlank() && newRelayUrl.startsWith("wss://")
                ) {
                    Text("Add", color = NostrordColors.Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showAddDialog = false
                    newRelayUrl = ""
                }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            }
        )
    }

    // Responsive layout
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp

        if (isCompact) {
            RelaySettingsMobile(
                listState = listState,
                relays = relays,
                currentRelay = currentRelay,
                onNavigate = onNavigate,
                onSelectRelay = { relayUrl ->
                    onNavigate(Screen.Home)
                    scope.launch {
                        NostrRepository.switchRelay(relayUrl)
                    }
                },
                onAddRelay = { showAddDialog = true }
            )
        } else {
            RelaySettingsDesktop(
                listState = listState,
                relays = relays,
                currentRelay = currentRelay,
                onNavigate = onNavigate,
                onSelectRelay = { relayUrl ->
                    onNavigate(Screen.Home)
                    scope.launch {
                        NostrRepository.switchRelay(relayUrl)
                    }
                },
                onAddRelay = { showAddDialog = true }
            )
        }
    }
}
