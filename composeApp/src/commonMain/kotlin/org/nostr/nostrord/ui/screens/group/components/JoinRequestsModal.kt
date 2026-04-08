package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.network.NostrGroupClient
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.avatars.Jdenticon
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Modal listing pending join requests with Accept/Reject buttons.
 */
@Composable
fun JoinRequestsModal(
    pendingRequests: List<NostrGroupClient.NostrMessage>,
    userMetadata: Map<String, UserMetadata>,
    onApprove: (pubkey: String) -> Unit,
    onReject: (eventId: String) -> Unit,
    onDismiss: () -> Unit
) {
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
                ),
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
                        Icon(
                            Icons.Default.Person,
                            contentDescription = null,
                            tint = NostrordColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Join Requests (${pendingRequests.size})",
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

                    if (pendingRequests.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(32.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No pending requests",
                                color = NostrordColors.TextMuted,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(vertical = 8.dp)
                        ) {
                            items(
                                items = pendingRequests,
                                key = { it.id }
                            ) { request ->
                                JoinRequestItem(
                                    request = request,
                                    metadata = userMetadata[request.pubkey],
                                    onApprove = { onApprove(request.pubkey) },
                                    onReject = { onReject(request.id) }
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
private fun JoinRequestItem(
    request: NostrGroupClient.NostrMessage,
    metadata: UserMetadata?,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Jdenticon(
                value = request.pubkey,
                size = 40.dp
            )
            if (!metadata?.picture.isNullOrBlank()) {
                val context = LocalPlatformContext.current
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(metadata?.picture)
                        .crossfade(true)
                        .memoryCachePolicy(CachePolicy.ENABLED)
                        .diskCachePolicy(CachePolicy.ENABLED)
                        .build(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop
                )
            }
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Name + pubkey
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = metadata?.displayName
                    ?: metadata?.name
                    ?: request.pubkey.take(8) + "...",
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (request.content.isNotBlank() && request.content != "/join") {
                Text(
                    text = request.content,
                    color = NostrordColors.TextMuted,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Reject button
        IconButton(
            onClick = onReject,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Reject",
                tint = NostrordColors.Error,
                modifier = Modifier.size(20.dp)
            )
        }

        // Approve button
        IconButton(
            onClick = onApprove,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                Icons.Default.Check,
                contentDescription = "Approve",
                tint = NostrordColors.Success,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
