package org.nostr.nostrord.ui.components.emoji

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

private val PICKER_WIDTH = 360.dp
private val PICKER_HEIGHT = 400.dp

private val EMOJI_GROUPS = EmojiGroup.entries.filter { it != EmojiGroup.RECENT }

@Composable
fun EmojiPicker(
    onEmojiSelect: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var searchQuery by remember { mutableStateOf("") }
    val gridState = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    // Build categorized emoji list from embedded data
    val categorizedEmojis: Map<EmojiGroup, List<EmojiEntry>> = remember {
        EmojiData.byGroup
    }

    // Rebuild items when recents change or search query changes
    val gridItems by remember {
        derivedStateOf {
            if (searchQuery.isNotBlank()) {
                val query = searchQuery.lowercase()
                val filtered = EMOJI_GROUPS.flatMap { group ->
                    (categorizedEmojis[group] ?: emptyList()).filter { entry ->
                        entry.name.lowercase().contains(query)
                    }
                }
                filtered.map<EmojiEntry, GridItem> { GridItem.EmojiCell(it) }
            } else {
                buildList {
                    val recents = RecentEmojiStore.recents
                    if (recents.isNotEmpty()) {
                        add(GridItem.Header(EmojiGroup.RECENT.label))
                        recents.forEach { add(GridItem.RecentCell(it)) }
                    }
                    EMOJI_GROUPS.forEach { group ->
                        val emojis = categorizedEmojis[group] ?: return@forEach
                        if (emojis.isNotEmpty()) {
                            add(GridItem.Header(group.label))
                            emojis.forEach { add(GridItem.EmojiCell(it)) }
                        }
                    }
                }
            }
        }
    }

    // Track active category from scroll position
    val activeCategory by remember {
        derivedStateOf {
            if (searchQuery.isNotBlank()) return@derivedStateOf EmojiGroup.SMILEYS
            // When scrolled to the very bottom, use last visible item to detect final category
            val layoutInfo = gridState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val atEnd = lastVisibleIndex >= gridItems.size - 1
            val targetIndex = if (atEnd) lastVisibleIndex else gridState.firstVisibleItemIndex

            var currentGroup = EmojiGroup.SMILEYS
            var index = 0
            for (item in gridItems) {
                if (index > targetIndex) break
                if (item is GridItem.Header) {
                    currentGroup = EmojiGroup.entries.find { it.label == item.title }
                        ?: currentGroup
                }
                index++
            }
            currentGroup
        }
    }

    // Compute header indices for category scrolling
    val headerIndices: Map<EmojiGroup, Int> = remember(gridItems) {
        val map = mutableMapOf<EmojiGroup, Int>()
        gridItems.forEachIndexed { index, item ->
            if (item is GridItem.Header) {
                val group = EmojiGroup.entries.find { it.label == item.title }
                if (group != null) map[group] = index
            }
        }
        map
    }

    Surface(
        shape = NostrordShapes.shapeMedium,
        color = NostrordColors.Surface,
        shadowElevation = 16.dp,
        modifier = modifier.width(PICKER_WIDTH).height(PICKER_HEIGHT)
    ) {
        Column {
            // Search bar
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = {
                    Text(
                        "Search emoji",
                        style = NostrordTypography.InputPlaceholder,
                        color = NostrordColors.TextMuted
                    )
                },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.Search,
                        contentDescription = null,
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier.size(Spacing.iconMd)
                    )
                },
                singleLine = true,
                textStyle = NostrordTypography.Input,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = NostrordColors.InputBackground,
                    unfocusedContainerColor = NostrordColors.InputBackground,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    cursorColor = NostrordColors.Primary,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.sm)
            )

            // Category tabs
            if (searchQuery.isBlank()) {
                EmojiCategoryBar(
                    activeCategory = activeCategory,
                    onCategoryClick = { group ->
                        val targetIndex = headerIndices[group] ?: return@EmojiCategoryBar
                        scope.launch { gridState.animateScrollToItem(targetIndex) }
                    },
                    modifier = Modifier.padding(horizontal = Spacing.xs)
                )
                HorizontalDivider(color = NostrordColors.BackgroundDark)
            }

            // Emoji grid
            EmojiPickerGrid(
                items = gridItems,
                onEmojiSelect = onEmojiSelect,
                gridState = gridState,
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.xs)
            )
        }
    }
}
