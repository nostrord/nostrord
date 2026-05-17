package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.buildGroupNaddr
import org.nostr.nostrord.ui.util.buildShareGroupLink
import org.nostr.nostrord.utils.rememberClipboardWriter
import org.nostr.nostrord.utils.rememberTextSharer
import org.nostr.nostrord.utils.supportsNativeShare

@Composable
fun ShareGroupModal(
    relayUrl: String,
    groupId: String,
    onDismiss: () -> Unit,
) {
    val link = buildShareGroupLink(relayUrl, groupId)
    val naddr = buildGroupNaddr(relayUrl, groupId)
    val copyToClipboard = rememberClipboardWriter()
    val shareText = rememberTextSharer()

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
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ShareRow(
                    label = "Link",
                    value = link,
                    actionIcon = if (supportsNativeShare) Icons.Default.Share else Icons.Default.ContentCopy,
                    actionLabel = if (supportsNativeShare) "Share" else "Copy",
                    onAction = { if (supportsNativeShare) shareText(link) else copyToClipboard(link) },
                )
                ShareRow(
                    label = "Nostr Address (naddr)",
                    value = naddr,
                    actionIcon = if (supportsNativeShare) Icons.Default.Share else Icons.Default.ContentCopy,
                    actionLabel = if (supportsNativeShare) "Share" else "Copy",
                    onAction = { if (supportsNativeShare) shareText(naddr) else copyToClipboard(naddr) },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = NostrordColors.TextSecondary)
            }
        },
    )
}

@Composable
private fun ShareRow(
    label: String,
    value: String,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector,
    actionLabel: String,
    onAction: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.labelSmall,
        )
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = value,
                color = NostrordColors.TextPrimary,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            IconButton(
                onClick = onAction,
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = actionIcon,
                    contentDescription = actionLabel,
                    tint = NostrordColors.Primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}
