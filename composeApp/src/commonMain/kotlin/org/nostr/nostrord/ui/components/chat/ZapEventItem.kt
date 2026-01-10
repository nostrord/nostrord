package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Zap event item (kind 9321 - nutzap) displaying Lightning payment.
 *
 * Layout follows MessageItem pattern:
 * [Sender Avatar] [Card: ₿ amount | Recipient avatar + name]
 *                 [         Emoji/message content          ]
 */
@Composable
fun ZapEventItem(
    senderPubkey: String,
    recipientPubkey: String,
    amount: Long,
    content: String,
    senderMetadata: UserMetadata? = null,
    recipientMetadata: UserMetadata? = null,
    onSenderClick: (String) -> Unit = {},
    onRecipientClick: (String) -> Unit = {}
) {
    // Sender display name
    val senderDisplayName = remember(senderMetadata?.displayName, senderMetadata?.name, senderPubkey) {
        senderMetadata?.displayName ?: senderMetadata?.name ?: senderPubkey.take(8) + "..."
    }

    // Recipient display name
    val recipientDisplayName = remember(recipientMetadata?.displayName, recipientMetadata?.name, recipientPubkey) {
        recipientMetadata?.displayName ?: recipientMetadata?.name ?: recipientPubkey.take(6)
    }

    // Chat bubble shape: rounded top corners and bottom-right, sharp bottom-left
    val zapShape = RoundedCornerShape(
        topStart = NostrordShapes.radiusMedium,
        topEnd = NostrordShapes.radiusMedium,
        bottomEnd = NostrordShapes.radiusMedium,
        bottomStart = NostrordShapes.radiusNone
    )

    // Bitcoin orange gradient for border
    val gradientBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFF7931A), // Bitcoin orange
            Color(0xFFFFAA00), // Lighter orange
            Color(0xFFF7931A)  // Bitcoin orange
        )
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = Spacing.messagePaddingHorizontal,
                end = Spacing.messagePaddingHorizontal,
                top = Spacing.messageGroupStart,
                bottom = Spacing.sm
            )
    ) {
        // Avatar column - same width as MessageItem (72dp total)
        Box(
            modifier = Modifier.width(Spacing.avatarColumnWidth - Spacing.messagePaddingHorizontal),
            contentAlignment = Alignment.TopStart
        ) {
            Box(
                modifier = Modifier
                    .size(Spacing.avatarSize)
                    .clip(CircleShape)
                    .clickable { onSenderClick(senderPubkey) }
                    .pointerHoverIcon(PointerIcon.Hand)
            ) {
                ProfileAvatar(
                    imageUrl = senderMetadata?.picture,
                    displayName = senderDisplayName,
                    pubkey = senderPubkey,
                    size = Spacing.avatarSize
                )
            }
        }

        // Zap card
        Column(
            modifier = Modifier
                .clip(zapShape)
                .border(
                    width = 1.dp,
                    brush = gradientBrush,
                    shape = zapShape
                )
                .background(NostrordColors.Background.copy(alpha = 0.9f))
                .padding(Spacing.sm)
        ) {
            // Header row: Bitcoin amount + recipient info
            Row(
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Bitcoin amount
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xxs)
                ) {
                    // Bitcoin symbol
                    Text(
                        text = "₿",
                        color = NostrordColors.TextMuted,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // Amount
                    Text(
                        text = formatSatsAmount(amount),
                        color = NostrordColors.TextPrimary,
                        style = NostrordTypography.Username.copy(
                            fontWeight = FontWeight.Medium
                        )
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.xxl))

                // Recipient info
                Row(
                    modifier = Modifier
                        .clickable { onRecipientClick(recipientPubkey) }
                        .pointerHoverIcon(PointerIcon.Hand),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    // Mini recipient avatar
                    Box(
                        modifier = Modifier
                            .size(Spacing.avatarSizeTiny)
                            .clip(CircleShape)
                    ) {
                        ProfileAvatar(
                            imageUrl = recipientMetadata?.picture,
                            displayName = recipientDisplayName,
                            pubkey = recipientPubkey,
                            size = Spacing.avatarSizeTiny
                        )
                    }
                    // Recipient name
                    Text(
                        text = recipientDisplayName,
                        color = NostrordColors.TextSecondary,
                        style = NostrordTypography.Caption
                    )
                }
            }

            // Emoji/message content (large)
            if (content.isNotBlank()) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = content,
                    fontSize = 48.sp,
                    lineHeight = 56.sp
                )
            }
        }
    }
}

/**
 * Format sats amount with appropriate suffix for large values.
 */
private fun formatSatsAmount(sats: Long): String {
    return when {
        sats >= 1_000_000 -> "${formatOneDecimal(sats / 1_000_000.0)}M"
        sats >= 1_000 -> "${formatOneDecimal(sats / 1_000.0)}k"
        else -> sats.toString()
    }
}

/**
 * Format a double to one decimal place (multiplatform compatible).
 */
private fun formatOneDecimal(value: Double): String {
    val rounded = (value * 10).toLong() / 10.0
    return if (rounded == rounded.toLong().toDouble()) {
        rounded.toLong().toString()
    } else {
        rounded.toString()
    }
}
