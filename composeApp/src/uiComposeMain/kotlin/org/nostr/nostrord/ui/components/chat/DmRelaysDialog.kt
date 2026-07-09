package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Where a peer's DMs route: their published kind:10050 relay list. Empty until the fetch lands,
 * or when the peer has published none (senders then fall back to defaults). Mirrors the web modal.
 */
@Composable
fun DmRelaysDialog(
    relays: List<String>,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NostrordColors.Surface,
        title = { Text("DM relays", color = NostrordColors.TextPrimary) },
        text = {
            if (relays.isEmpty()) {
                Text(
                    "No published DM relay list yet. Messages route to default relays.",
                    color = NostrordColors.TextMuted,
                    fontSize = 13.sp,
                )
            } else {
                Column {
                    relays.forEach { relay ->
                        Text(
                            relay,
                            color = NostrordColors.TextContent,
                            fontSize = 13.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = Spacing.xxs),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
