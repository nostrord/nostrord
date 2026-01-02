package org.nostr.nostrord.ui.components.badges

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Unread message count badge.
 * Shows a red dot for 0 unread or a count badge for 1+.
 */
@Composable
fun UnreadBadge(
    count: Int,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    backgroundColor: Color = NostrordColors.Error,
    textColor: Color = Color.White
) {
    if (count <= 0) return

    val displayText = if (count > 99) "99+" else count.toString()
    val shape = RoundedCornerShape(size / 2)

    Box(
        modifier = modifier
            .widthIn(min = size)
            .clip(shape)
            .background(backgroundColor)
            .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = displayText,
            color = textColor,
            fontSize = (size.value * 0.55f).sp,
            fontWeight = FontWeight.Bold,
            style = NostrordTypography.Badge
        )
    }
}

/**
 * Simple unread indicator dot.
 * Used when we don't need to show count, just presence of unread messages.
 */
@Composable
fun UnreadDot(
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    color: Color = NostrordColors.Error
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color)
    )
}
