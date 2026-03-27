package org.nostr.nostrord.ui.components.emoji

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EmojiEmotions
import androidx.compose.material.icons.outlined.EmojiNature
import androidx.compose.material.icons.outlined.EmojiFoodBeverage
import androidx.compose.material.icons.outlined.EmojiTransportation
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material.icons.outlined.EmojiObjects
import androidx.compose.material.icons.outlined.EmojiSymbols
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.EmojiPeople
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

enum class EmojiGroup(val label: String, val icon: ImageVector, val groupKey: String) {
    RECENT("Recent", Icons.Outlined.Schedule, ""),
    SMILEYS("Smileys", Icons.Outlined.EmojiEmotions, "Smileys & Emotion"),
    PEOPLE("People", Icons.Outlined.EmojiPeople, "People & Body"),
    NATURE("Nature", Icons.Outlined.EmojiNature, "Animals & Nature"),
    FOOD("Food", Icons.Outlined.EmojiFoodBeverage, "Food & Drink"),
    TRAVEL("Travel", Icons.Outlined.EmojiTransportation, "Travel & Places"),
    ACTIVITIES("Activities", Icons.Outlined.EmojiEvents, "Activities"),
    OBJECTS("Objects", Icons.Outlined.EmojiObjects, "Objects"),
    SYMBOLS("Symbols", Icons.Outlined.EmojiSymbols, "Symbols"),
    FLAGS("Flags", Icons.Outlined.Flag, "Flags"),
}

@Composable
fun EmojiCategoryBar(
    activeCategory: EmojiGroup,
    onCategoryClick: (EmojiGroup) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasRecents = RecentEmojiStore.recents.isNotEmpty()

    Row(modifier = modifier.fillMaxWidth()) {
        EmojiGroup.entries.forEach { group ->
            if (group == EmojiGroup.RECENT && !hasRecents) return@forEach

            IconButton(
                onClick = { onCategoryClick(group) },
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = group.icon,
                    contentDescription = group.label,
                    tint = if (activeCategory == group) NostrordColors.Primary
                           else NostrordColors.TextMuted,
                    modifier = Modifier.size(Spacing.iconMd)
                )
            }
        }
    }
}
