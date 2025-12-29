package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.generateColorFromString
import org.nostr.nostrord.utils.formatTimestamp

/**
 * Enhanced system event item with avatars and grouping support.
 * Shows join/leave events with user avatars and supports displaying
 * multiple users when events are grouped together.
 */
@Composable
fun SystemEventItem(
    pubkey: String,
    action: String,
    createdAt: Long,
    metadata: UserMetadata? = null,
    additionalUsers: List<String> = emptyList(),
    allUserMetadata: Map<String, UserMetadata> = emptyMap()
) {
    val isJoinEvent = action.contains("joined", ignoreCase = true)
    val totalUsers = 1 + additionalUsers.size
    val accentColor = if (isJoinEvent) NostrordColors.Success else NostrordColors.TextMuted

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        // Icon with accent background
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(accentColor.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isJoinEvent) Icons.AutoMirrored.Filled.Login else Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = accentColor
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // Stacked avatars for grouped events
        if (totalUsers > 1) {
            StackedAvatars(
                primaryPubkey = pubkey,
                primaryMetadata = metadata,
                additionalPubkeys = additionalUsers,
                allMetadata = allUserMetadata,
                maxVisible = 4
            )
            Spacer(modifier = Modifier.width(10.dp))
        } else {
            // Single avatar
            MiniAvatar(
                pubkey = pubkey,
                metadata = metadata,
                size = 20
            )
            Spacer(modifier = Modifier.width(8.dp))
        }

        // Event text
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (totalUsers == 1) {
                    val displayName = metadata?.displayName ?: metadata?.name ?: pubkey.take(8)
                    Text(
                        text = displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = action,
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        text = "$totalUsers members",
                        color = Color.White,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = action,
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Timestamp
        Text(
            text = formatTimestamp(createdAt),
            color = NostrordColors.TextMuted.copy(alpha = 0.6f),
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
private fun StackedAvatars(
    primaryPubkey: String,
    primaryMetadata: UserMetadata?,
    additionalPubkeys: List<String>,
    allMetadata: Map<String, UserMetadata>,
    maxVisible: Int = 4
) {
    val allPubkeys = listOf(primaryPubkey) + additionalPubkeys
    val visiblePubkeys = allPubkeys.take(maxVisible)
    val overflow = allPubkeys.size - maxVisible

    Row(
        horizontalArrangement = Arrangement.spacedBy((-8).dp)
    ) {
        visiblePubkeys.forEachIndexed { index, pk ->
            val meta = if (index == 0) primaryMetadata else allMetadata[pk]
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(NostrordColors.Background)
                    .padding(1.dp)
            ) {
                MiniAvatar(pubkey = pk, metadata = meta, size = 22)
            }
        }

        // Overflow indicator
        if (overflow > 0) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(NostrordColors.Surface),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+$overflow",
                    color = NostrordColors.TextSecondary,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

@Composable
private fun MiniAvatar(
    pubkey: String,
    metadata: UserMetadata?,
    size: Int = 20
) {
    val displayName = metadata?.displayName ?: metadata?.name ?: pubkey.take(2)

    if (!metadata?.picture.isNullOrBlank()) {
        AsyncImage(
            model = metadata?.picture,
            contentDescription = displayName,
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    } else {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(generateColorFromString(pubkey)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = displayName.take(1).uppercase(),
                color = Color.White,
                fontSize = (size * 0.45f).sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
