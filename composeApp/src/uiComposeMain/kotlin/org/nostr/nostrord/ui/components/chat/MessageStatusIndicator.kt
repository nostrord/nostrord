package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Optimistic-send status row shown under the author's own message. Sending: a muted "Sending..."
 * hint. Failed: the reason plus Retry / Dismiss. Shared by chat (MessageItem) and threads so the
 * indicator stays identical; the caller guards on "own message and status != null".
 */
@Composable
fun MessageStatusIndicator(
    status: GroupManager.MessageStatus,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (status) {
        is GroupManager.MessageStatus.Sending -> {
            Text(
                text = "Sending...",
                color = NostrordColors.TextMuted,
                style = NostrordTypography.Timestamp,
                modifier = Modifier.padding(top = Spacing.xxs),
            )
        }
        is GroupManager.MessageStatus.Failed -> {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = Spacing.xxs),
            ) {
                Text(text = "Not delivered", color = NostrordColors.Error, style = NostrordTypography.Timestamp)
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "Retry",
                    color = NostrordColors.Error,
                    style = NostrordTypography.Timestamp,
                    modifier = Modifier.clickable { onRetry() },
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                Text(
                    text = "Dismiss",
                    color = NostrordColors.TextMuted,
                    style = NostrordTypography.Timestamp,
                    modifier = Modifier.clickable { onDismiss() },
                )
            }
        }
    }
}
