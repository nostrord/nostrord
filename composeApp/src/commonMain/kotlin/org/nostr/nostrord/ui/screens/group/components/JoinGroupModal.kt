package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.layout.*
import org.nostr.nostrord.utils.toRelayUrl
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Modal for joining a group by pasting an invite link.
 * Accepts formats:
 * - Full URL: https://nostrord.com/open/?relay=X&group=Y&code=Z
 * - nostrord://open?relay=X&group=Y&code=Z
 */
@Composable
fun JoinGroupModal(
    onJoin: (relayUrl: String, groupId: String, inviteCode: String?) -> Unit,
    onDismiss: () -> Unit
) {
    var linkInput by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }

    fun tryParseLink(input: String): Triple<String, String, String?>? {
        if (input.isBlank()) return null
        val trimmed = input.trim()

        val queryStart = trimmed.indexOf('?')
        if (queryStart < 0) return null

        val query = trimmed.substring(queryStart + 1)
        val params = query.split("&").associate { param ->
            val idx = param.indexOf("=")
            if (idx >= 0) param.substring(0, idx) to param.substring(idx + 1)
            else param to ""
        }

        val relay = params["relay"]?.takeIf { it.isNotBlank() } ?: return null
        val group = params["group"]?.takeIf { it.isNotBlank() } ?: return null
        val relayUrl = relay.toRelayUrl()
        val code = params["code"]?.takeIf { it.isNotBlank() }

        return Triple(relayUrl, group, code)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NostrordColors.Surface,
        title = {
            Text(
                "Join Group",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Paste an invite link to join a group.",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = linkInput,
                    onValueChange = {
                        linkInput = it
                        parseError = null
                    },
                    placeholder = {
                        Text(
                            "https://nostrord.com/open/?relay=...&group=...",
                            color = NostrordColors.TextMuted,
                            style = MaterialTheme.typography.bodySmall
                        )
                    },
                    label = { Text("Invite Link", color = NostrordColors.TextSecondary) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = outlinedFieldColors(),
                    textStyle = MaterialTheme.typography.bodyMedium,
                    shape = RoundedCornerShape(8.dp)
                )

                if (parseError != null) {
                    Text(
                        parseError!!,
                        color = NostrordColors.Error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsed = tryParseLink(linkInput)
                    if (parsed == null) {
                        parseError = "Invalid link. Use a nostrord.com/open/ or nostrord:// invite link."
                        return@Button
                    }
                    onJoin(parsed.first, parsed.second, parsed.third)
                },
                enabled = linkInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NostrordColors.Primary,
                    contentColor = Color.White,
                    disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.3f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NostrordColors.TextSecondary)
            }
        }
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
    unfocusedLabelColor = NostrordColors.TextSecondary
)
