package org.nostr.nostrord.ui.screens.relay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.screens.relay.components.AddRelayCard
import org.nostr.nostrord.ui.screens.relay.components.RelayCard
import org.nostr.nostrord.ui.screens.relay.model.RelayInfo
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun RelaySettingsDesktop(
    listState: LazyListState,
    relays: List<RelayInfo>,
    onNavigate: (Screen) -> Unit,
    onSelectRelay: (String) -> Unit,
    onAddRelay: () -> Unit,
    onDeleteRelay: ((String) -> Unit)? = null,
    lazyFetchStates: Map<String, Boolean> = emptyMap(),
    onToggleLazyFetch: ((String, Boolean) -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NostrordColors.Background)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .background(NostrordColors.BackgroundDark)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Header icon
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(NostrordColors.Primary.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Public,
                    contentDescription = null,
                    tint = NostrordColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column {
                Text(
                    text = "Relay Settings",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "Manage your NIP-29 group relays",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Content with centered max-width container
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.TopCenter
        ) {
            Box(
                modifier = Modifier
                    .widthIn(max = 800.dp)
                    .fillMaxHeight()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Section header
                    item {
                        Column(modifier = Modifier.padding(bottom = 8.dp)) {
                            Text(
                                text = "Connected Relays",
                                color = NostrordColors.TextSecondary,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "${relays.size} relay${if (relays.size != 1) "s" else ""} configured",
                                color = NostrordColors.TextMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    items(relays) { relay ->
                        RelayCard(
                            relay = relay,
                            isCompact = false,
                            onSelectRelay = { onSelectRelay(relay.url) },
                            onDeleteRelay = onDeleteRelay?.let { cb -> { cb(relay.url) } },
                            isLazyFetch = lazyFetchStates[relay.url] ?: false,
                            onToggleLazyFetch = onToggleLazyFetch?.let { cb -> { v -> cb(relay.url, v) } }
                        )
                    }

                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        AddRelayCard(isCompact = false, onClick = onAddRelay)
                    }

                    // Bottom spacing
                    item {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                VerticalScrollbarWrapper(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }
}
