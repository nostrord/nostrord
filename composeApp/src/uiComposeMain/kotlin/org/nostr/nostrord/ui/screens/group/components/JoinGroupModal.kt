package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.parseGroupJoinInput

/**
 * Modal for joining a group by pasting an invite link or a NIP-29 group address.
 * Accepts formats:
 * - Group address: wss://relay.com'groupId
 * - Full URL: https://nostrord.com/open/?relay=X&group=Y&code=Z
 * - nostrord://open?relay=X&group=Y&code=Z
 */
@Composable
fun JoinGroupModal(
    onJoin: (relayUrl: String, groupId: String, inviteCode: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var linkInput by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NostrordColors.Surface,
        title = {
            Text(
                "Join Group",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Paste a wss://relay'id, a naddr, or a nostrord invite link.",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                )

                // Standalone label above the field, mirroring the web `field-label` +
                // placeholder structure (not Material's in-field floating label).
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "Group address, naddr, or invite link",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedTextField(
                        value = linkInput,
                        onValueChange = {
                            linkInput = it
                            parseError = null
                        },
                        placeholder = {
                            Text(
                                "wss://relay.com'groupId",
                                color = NostrordColors.TextMuted,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = outlinedFieldColors(),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        shape = RoundedCornerShape(8.dp),
                    )
                }

                if (parseError != null) {
                    Text(
                        parseError!!,
                        color = NostrordColors.Error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = parseGroupJoinInput(linkInput)
                    if (parsed == null) {
                        parseError = "Invalid input. Use a wss://relay'groupId address or a nostrord invite link."
                        return@Button
                    }
                    onJoin(parsed.relayUrl, parsed.groupId, parsed.inviteCode)
                },
                enabled = linkInput.isNotBlank(),
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = NostrordColors.Primary,
                    contentColor = Color.White,
                    disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.3f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f),
                ),
                shape = RoundedCornerShape(8.dp),
            ) {
                Text("Open")
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
private fun outlinedFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = NostrordColors.Primary,
    unfocusedBorderColor = NostrordColors.SurfaceVariant,
    cursorColor = NostrordColors.Primary,
    focusedLabelColor = NostrordColors.Primary,
    unfocusedLabelColor = NostrordColors.TextSecondary,
)
