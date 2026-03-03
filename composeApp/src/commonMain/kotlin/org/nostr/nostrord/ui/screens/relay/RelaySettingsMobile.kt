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
import org.nostr.nostrord.ui.screens.relay.components.AddRelayCard
import org.nostr.nostrord.ui.screens.relay.components.RelayCard
import org.nostr.nostrord.ui.screens.relay.model.RelayInfo
import org.nostr.nostrord.ui.theme.NostrordColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RelaySettingsMobile(
    listState: LazyListState,
    relays: List<RelayInfo>,
    currentRelay: String,
    onNavigate: (Screen) -> Unit,
    onSelectRelay: (String) -> Unit,
    onAddRelay: () -> Unit,
    onDeleteRelay: ((String) -> Unit)? = null
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Header icon
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(NostrordColors.Primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Public,
                                contentDescription = null,
                                tint = NostrordColors.Primary,
                                modifier = Modifier.size(18.dp)
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
                                text = "Manage NIP-29 relays",
                                color = NostrordColors.TextSecondary,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NostrordColors.BackgroundDark
                )
            )
        },
        containerColor = NostrordColors.Background
    ) { paddingValues ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Section header
            item {
                Text(
                    text = "${relays.size} relay${if (relays.size != 1) "s" else ""} configured",
                    color = NostrordColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            items(relays) { relay ->
                RelayCard(
                    relay = relay,
                    isActive = relay.url == currentRelay,
                    isCompact = true,
                    onSelectRelay = { onSelectRelay(relay.url) },
                    onDeleteRelay = if (onDeleteRelay != null && relay.url != currentRelay) {
                        { onDeleteRelay(relay.url) }
                    } else null
                )
            }

            item {
                Spacer(modifier = Modifier.height(4.dp))
                AddRelayCard(isCompact = true, onClick = onAddRelay)
            }

            // Bottom spacing for safe area
            item {
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
