package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Shown in place of the message list when a group is orphaned: pinned in the joined list
 * (kind:10009) but the relay served no kind:39000 after its group list finished, i.e. the group
 * was deleted or no longer exists. Mirrors the web "Group no longer available" panel and turns a
 * dead-end loading state into an action (forget the stale pin).
 */
@Composable
internal fun OrphanedGroupContent(onForget: () -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxSize()
            .background(NostrordColors.BackgroundDark)
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Default.Block,
            contentDescription = null,
            tint = NostrordColors.TextMuted,
            modifier = Modifier.size(40.dp),
        )
        Spacer(modifier = Modifier.height(Spacing.md))
        Text(
            text = "Group no longer available",
            color = NostrordColors.TextSecondary,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Text(
            text = "This group has been deleted or is no longer on the relay.",
            color = NostrordColors.TextMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        AppButton(
            text = "Remove from your list",
            onClick = onForget,
            variant = AppButtonVariant.Secondary,
        )
    }
}
