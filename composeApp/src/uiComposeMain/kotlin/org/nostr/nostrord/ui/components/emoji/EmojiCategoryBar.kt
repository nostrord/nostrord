package org.nostr.nostrord.ui.components.emoji

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.EmojiFoodBeverage
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material.icons.outlined.EmojiObjects
import androidx.compose.material.icons.outlined.EmojiPeople
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.EmojiTransportation
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/** Category icon for the tab bar (the EmojiGroup enum itself is shared, pure, in commonMain). */
private fun EmojiGroup.toIcon(): ImageVector = when (this) {
    EmojiGroup.RECENT -> Icons.Outlined.Schedule
    EmojiGroup.SMILEYS -> Icons.Outlined.EmojiEmotions
    EmojiGroup.PEOPLE -> Icons.Outlined.EmojiPeople
    EmojiGroup.NATURE -> Icons.Outlined.EmojiNature
    EmojiGroup.FOOD -> Icons.Outlined.EmojiFoodBeverage
    EmojiGroup.TRAVEL -> Icons.Outlined.EmojiTransportation
    EmojiGroup.ACTIVITIES -> Icons.Outlined.EmojiEvents
    EmojiGroup.OBJECTS -> Icons.Outlined.EmojiObjects
    EmojiGroup.SYMBOLS -> Icons.Outlined.EmojiSymbols
    EmojiGroup.FLAGS -> Icons.Outlined.Flag
}

@Composable
fun EmojiCategoryBar(
    activeCategory: EmojiGroup,
    onCategoryClick: (EmojiGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasRecents = RecentEmojiStore.recents.isNotEmpty()

    Row(modifier = modifier.fillMaxWidth()) {
        EmojiGroup.entries.forEach { group ->
            if (group == EmojiGroup.RECENT && !hasRecents) return@forEach

            IconButton(
                onClick = { onCategoryClick(group) },
                modifier = Modifier.size(36.dp),
            ) {
                Icon(
                    imageVector = group.toIcon(),
                    contentDescription = group.label,
                    tint =
                    if (activeCategory == group) {
                        NostrordColors.Primary
                    } else {
                        NostrordColors.TextMuted
                    },
                    modifier = Modifier.size(Spacing.iconMd),
                )
            }
        }
    }
}
