package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.nostr.nostrord.utils.rememberClipboardWriter
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Data class representing an invite code parsed from kind 9009 messages.
 */
data class InviteCode(
    val code: String,
    val createdAt: Long,
    val eventId: String
)

/**
 * Modal for managing invite codes (admin only, closed groups).
 */
@Composable
fun InviteCodesModal(
    inviteCodes: List<InviteCode>,
    onCreateInviteCode: () -> Unit,
    onRevokeInviteCode: (eventId: String) -> Unit,
    onDismiss: () -> Unit,
    relayUrl: String,
    groupId: String,
    isCreating: Boolean = false,
    createdCode: String? = null,
    errorMessage: String? = null,
    onClearError: () -> Unit = {}
) {
    val copyToClipboard = rememberClipboardWriter()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 420.dp)
                    .fillMaxWidth(0.9f)
                    .heightIn(max = 500.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
            ) {
                Column {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NostrordColors.BackgroundDark)
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Invite Codes",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        // Create button
                        Button(
                            onClick = onCreateInviteCode,
                            enabled = !isCreating,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NostrordColors.Primary,
                                contentColor = Color.White,
                                disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isCreating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(Spacing.sm))
                            } else {
                                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(Spacing.sm))
                            }
                            Text("Create Invite Code", style = NostrordTypography.Button)
                        }

                        // Error message (e.g. relay doesn't support kind 9009)
                        if (errorMessage != null) {
                            Spacer(modifier = Modifier.height(Spacing.md))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(NostrordColors.Error.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                                    .padding(Spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = errorMessage,
                                    style = NostrordTypography.Caption,
                                    color = NostrordColors.Error,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = onClearError,
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Dismiss",
                                        tint = NostrordColors.Error,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }

                        // Show recently created code
                        if (createdCode != null) {
                            Spacer(modifier = Modifier.height(Spacing.md))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
                                    .padding(Spacing.md),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Invite Code Created",
                                        style = NostrordTypography.Caption,
                                        color = NostrordColors.Success,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(Spacing.xs))
                                    Text(
                                        text = createdCode,
                                        style = NostrordTypography.MessageBody,
                                        color = NostrordColors.TextPrimary,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                IconButton(
                                    onClick = { copyToClipboard(createdCode) },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.ContentCopy,
                                        contentDescription = "Copy code",
                                        tint = NostrordColors.TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        val url = buildInviteUrl(relayUrl, groupId, createdCode)
                                        copyToClipboard(url)
                                    },
                                    modifier = Modifier.size(36.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Link,
                                        contentDescription = "Copy invite URL",
                                        tint = NostrordColors.TextSecondary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // Active codes list
                        if (inviteCodes.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(Spacing.lg))
                            Text(
                                text = "Active Codes (${inviteCodes.size})",
                                style = NostrordTypography.Caption,
                                color = NostrordColors.TextSecondary,
                                fontWeight = FontWeight.Medium
                            )
                            Spacer(modifier = Modifier.height(Spacing.sm))

                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                items(inviteCodes, key = { it.eventId }) { invite ->
                                    InviteCodeItem(
                                        invite = invite,
                                        onCopyCode = { copyToClipboard(invite.code) },
                                        onCopyUrl = {
                                            val url = buildInviteUrl(relayUrl, groupId, invite.code)
                                            copyToClipboard(url)
                                        },
                                        onRevoke = { onRevokeInviteCode(invite.eventId) }
                                    )
                                }
                            }
                        } else if (!isCreating && createdCode == null) {
                            Spacer(modifier = Modifier.height(Spacing.lg))
                            Box(
                                modifier = Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No active invite codes",
                                    style = NostrordTypography.Caption,
                                    color = NostrordColors.TextMuted
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun InviteCodeItem(
    invite: InviteCode,
    onCopyCode: () -> Unit,
    onCopyUrl: () -> Unit,
    onRevoke: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = invite.code,
            style = NostrordTypography.Caption,
            color = NostrordColors.TextPrimary,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onCopyCode, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.ContentCopy,
                contentDescription = "Copy code",
                tint = NostrordColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(onClick = onCopyUrl, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Link,
                contentDescription = "Copy URL",
                tint = NostrordColors.TextSecondary,
                modifier = Modifier.size(16.dp)
            )
        }
        IconButton(onClick = onRevoke, modifier = Modifier.size(32.dp)) {
            Icon(
                Icons.Default.Delete,
                contentDescription = "Revoke",
                tint = NostrordColors.Error,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/**
 * Builds an invite URL with code appended to the share link.
 */
private fun buildInviteUrl(relayUrl: String, groupId: String, code: String): String {
    return org.nostr.nostrord.ui.util.buildShareGroupLink(relayUrl, groupId) + "&code=$code"
}

/**
 * Dialog for entering an invite code to join a closed group.
 */
@Composable
fun InviteCodeInputDialog(
    onSubmit: (code: String) -> Unit,
    onDismiss: () -> Unit
) {
    var code by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
        ) {
            Column(
                modifier = Modifier.padding(Spacing.lg)
            ) {
                Text(
                    text = "Enter Invite Code",
                    style = MaterialTheme.typography.titleMedium,
                    color = NostrordColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "This group requires an invite code to join.",
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextSecondary
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.trim() },
                    placeholder = {
                        Text("Paste invite code", color = NostrordColors.TextMuted)
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = NostrordColors.TextPrimary,
                        unfocusedTextColor = NostrordColors.TextPrimary,
                        focusedBorderColor = NostrordColors.Primary,
                        unfocusedBorderColor = NostrordColors.SurfaceVariant,
                        cursorColor = NostrordColors.Primary
                    ),
                    textStyle = NostrordTypography.MessageBody.copy(fontFamily = FontFamily.Monospace)
                )
                Spacer(modifier = Modifier.height(Spacing.md))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Cancel", color = NostrordColors.TextSecondary)
                    }
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    Button(
                        onClick = { onSubmit(code) },
                        enabled = code.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = NostrordColors.Primary,
                            contentColor = Color.White,
                            disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Join", style = NostrordTypography.Button)
                    }
                }
            }
        }
    }
}
