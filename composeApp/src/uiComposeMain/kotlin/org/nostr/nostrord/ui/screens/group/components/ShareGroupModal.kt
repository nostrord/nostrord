package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.IdentifierRow
import org.nostr.nostrord.ui.groupIdentifiers
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun ShareGroupModal(
    relayUrl: String,
    groupId: String,
    onDismiss: () -> Unit,
) {
    val relayMetadata by AppModule.nostrRepository.relayMetadata.collectAsState()
    val relayPubkey = relayMetadata[relayUrl]?.pubkey ?: relayMetadata[relayUrl.trimEnd('/')]?.pubkey

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NostrordColors.Surface,
        title = {
            Text(
                "Share Group",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        },
        // A single cycling identifier field (relay'groupId / naddr / nostrord link) instead of one
        // copy input per format. Shares the .identifier-* object with the profile + group-info UIs.
        text = {
            IdentifierRow(ids = groupIdentifiers(relayUrl, groupId, relayPubkey))
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = NostrordColors.TextSecondary)
            }
        },
    )
}
