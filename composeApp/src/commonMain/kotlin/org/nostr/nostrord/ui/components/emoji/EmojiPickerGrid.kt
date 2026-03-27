package org.nostr.nostrord.ui.components.emoji

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily

sealed class GridItem {
    data class Header(val title: String) : GridItem()
    data class EmojiCell(val entry: EmojiEntry) : GridItem()
    data class RecentCell(val emojiString: String) : GridItem()
}

@Composable
fun EmojiPickerGrid(
    items: List<GridItem>,
    onEmojiSelect: (String) -> Unit,
    gridState: LazyGridState,
    modifier: Modifier = Modifier
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(8),
        state = gridState,
        modifier = modifier
    ) {
        items(
            count = items.size,
            key = { index ->
                when (val item = items[index]) {
                    is GridItem.Header -> "header_${item.title}"
                    is GridItem.EmojiCell -> "emoji_${index}_${item.entry.emoji}"
                    is GridItem.RecentCell -> "recent_${item.emojiString}"
                }
            },
            span = { index ->
                when (items[index]) {
                    is GridItem.Header -> GridItemSpan(maxLineSpan)
                    else -> GridItemSpan(1)
                }
            }
        ) { index ->
            when (val item = items[index]) {
                is GridItem.Header -> {
                    Text(
                        text = item.title.uppercase(),
                        style = NostrordTypography.SectionHeader,
                        color = NostrordColors.TextMuted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                    )
                }
                is GridItem.EmojiCell -> {
                    EmojiGridCell(
                        emojiString = item.entry.emoji,
                        onClick = {
                            onEmojiSelect(item.entry.emoji)
                            RecentEmojiStore.recordUsage(item.entry.emoji)
                        }
                    )
                }
                is GridItem.RecentCell -> {
                    EmojiGridCell(
                        emojiString = item.emojiString,
                        onClick = {
                            onEmojiSelect(item.emojiString)
                            RecentEmojiStore.recordUsage(item.emojiString)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun EmojiGridCell(
    emojiString: String,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val emojiFontFamily = rememberEmojiFontFamily()

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(40.dp)
            .clip(NostrordShapes.shapeSmall)
            .hoverable(interactionSource)
            .background(if (isHovered) NostrordColors.HoverBackground else NostrordColors.Surface)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
    ) {
        Text(
            text = emojiString,
            fontSize = 24.sp,
            fontFamily = emojiFontFamily
        )
    }
}
