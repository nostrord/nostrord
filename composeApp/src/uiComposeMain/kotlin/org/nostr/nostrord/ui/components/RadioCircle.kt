package org.nostr.nostrord.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Web-style radio circle: 18dp outer ring with a 2dp border, filling to a 8dp
 * primary-color dot when selected. Matches `.settings-radio` in styles.css so the
 * two platforms read the same. Caller controls the row layout / hover / click.
 */
@Composable
fun RadioCircle(
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(18.dp)
            .clip(CircleShape)
            .border(
                width = 2.dp,
                color = if (selected) NostrordColors.Primary else NostrordColors.TextMuted,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(NostrordColors.Primary),
            )
        }
    }
}
