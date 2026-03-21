package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.isValidIconUrl
import org.nostr.nostrord.ui.util.buildRelayIconRequest
import org.nostr.nostrord.ui.util.relayFallbackPainter
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import org.nostr.nostrord.utils.rememberClipboardWriter
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.loading.ConnectionErrorState
import org.nostr.nostrord.ui.components.loading.GroupCardSkeleton
import org.nostr.nostrord.ui.components.navigation.relayShortLabel
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.screens.home.components.PickGroupCard
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.generateColorFromString

enum class GroupFilter { All, Joined }

@Composable
fun HomeScreenDesktop(
    gridState: LazyGridState,
    onNavigate: (Screen) -> Unit,
    joinedGroups: Set<String>,
    groups: List<GroupMetadata>,
    filteredGroups: List<GroupMetadata>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    currentRelayUrl: String,
    relayMeta: Nip11RelayInfo? = null,
    activeFilter: GroupFilter,
    onFilterChange: (GroupFilter) -> Unit,
    isLoading: Boolean = false,
    hasError: Boolean = false,
    onRetry: () -> Unit = {},
    onRemoveRelay: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NostrordColors.Background)
    ) {
        // pick-group-header: centered relay icon + title + description
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(NostrordColors.Background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp, start = 24.dp, end = 24.dp, bottom = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val relayLabel = relayMeta?.name?.takeIf { it.isNotBlank() }
                    ?: relayShortLabel(currentRelayUrl)

                RelayHeaderIcon(
                    relayUrl = currentRelayUrl,
                    iconUrl = relayMeta?.icon,
                    label = relayLabel,
                    size = 64.dp
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = relayLabel,
                    color = NostrordColors.TextPrimary,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "Choose a group to join and start chatting.",
                    color = NostrordColors.TextMuted,
                    fontSize = 14.sp
                )
            }

            // Relay options menu — position:absolute; top:8px; right:8px
            RelayOptionsMenu(
                relayUrl = currentRelayUrl,
                onRemoveRelay = onRemoveRelay,
                modifier = Modifier.align(Alignment.TopEnd)
            )
        }

        // Scrollable content area
        Box(modifier = Modifier.weight(1f)) {
            when {
                hasError -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        ConnectionErrorState(onRetry = onRetry)
                    }
                }
                isLoading && groups.isEmpty() -> {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(minSize = 280.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(6) { GroupCardSkeleton() }
                    }
                }
                else -> {
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Adaptive(minSize = 280.dp),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Filter bar
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            FilterBar(activeFilter = activeFilter, onFilterChange = onFilterChange)
                        }

                        // Search input
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            PickGroupSearch(
                                query = searchQuery,
                                onQueryChange = onSearchChange
                            )
                        }

                        if (filteredGroups.isEmpty()) {
                            item(span = { GridItemSpan(maxLineSpan) }) {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (searchQuery.isNotBlank()) "No groups match \"$searchQuery\""
                                               else "No groups found",
                                        color = NostrordColors.TextMuted,
                                        fontSize = 14.sp
                                    )
                                }
                            }
                        } else {
                            items(filteredGroups, key = { it.id }) { group ->
                                PickGroupCard(
                                    group = group,
                                    isJoined = group.id in joinedGroups,
                                    onClick = { onNavigate(Screen.Group(group.id, group.name)) }
                                )
                            }
                        }
                    }

                    VerticalScrollbarWrapper(
                        gridState = gridState,
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun FilterBar(activeFilter: GroupFilter, onFilterChange: (GroupFilter) -> Unit) {
    val filters = listOf(
        GroupFilter.All to "All",
        GroupFilter.Joined to "Joined"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 2.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        filters.forEach { (filter, label) ->
            FilterChip(
                label = label,
                isActive = activeFilter == filter,
                onClick = { onFilterChange(filter) }
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, isActive: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bg = when {
        isActive -> NostrordColors.Primary
        isHovered -> NostrordColors.SurfaceVariant
        else -> NostrordColors.BackgroundDark
    }
    val textColor = when {
        isActive -> Color.White
        isHovered -> NostrordColors.TextSecondary
        else -> NostrordColors.TextMuted
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 12.dp, vertical = 5.dp)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun PickGroupSearch(query: String, onQueryChange: (String) -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(NostrordColors.BackgroundDark)
            .height(40.dp)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search groups...",
                        color = NostrordColors.TextMuted,
                        fontSize = 13.sp
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = TextStyle(
                        color = NostrordColors.TextPrimary,
                        fontSize = 13.sp
                    ),
                    cursorBrush = SolidColor(NostrordColors.Primary),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
internal fun RelayOptionsMenu(
    relayUrl: String,
    onRemoveRelay: () -> Unit,
    modifier: Modifier = Modifier
) {
    val copyToClipboard = rememberClipboardWriter()
    var expanded by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Remove relay?") },
            text = { Text("This will disconnect and remove ${relayUrl.removePrefix("wss://")} from your relay list.") },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    showConfirm = false
                    onRemoveRelay()
                }) {
                    Text("Remove", color = NostrordColors.Error)
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            }
        )
    }

    // Box wraps IconButton + DropdownMenu so the dropdown anchors to the button
    Box(modifier = modifier.wrapContentSize(Alignment.TopEnd)) {
        IconButton(onClick = { expanded = true }) {
            Icon(
                Icons.Default.MoreVert,
                contentDescription = "Relay options",
                tint = NostrordColors.TextMuted
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = NostrordColors.Surface
        ) {
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("📋", fontSize = 14.sp)
                        Text("Copy relay URL", color = NostrordColors.TextPrimary, fontSize = 14.sp)
                    }
                },
                onClick = {
                    copyToClipboard(relayUrl)
                    expanded = false
                }
            )
            HorizontalDivider(color = NostrordColors.Divider)
            DropdownMenuItem(
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("✕", fontSize = 14.sp, color = NostrordColors.Error)
                        Text("Remove relay", color = NostrordColors.Error, fontSize = 14.sp)
                    }
                },
                onClick = {
                    expanded = false
                    showConfirm = true
                }
            )
        }
    }
}

@Composable
private fun RelayHeaderIcon(
    relayUrl: String,
    iconUrl: String?,
    label: String,
    size: androidx.compose.ui.unit.Dp
) {
    val context = LocalPlatformContext.current
    val fallbackPainter = if (iconUrl.isNullOrBlank()) relayFallbackPainter(relayUrl) else null
    val hasIcon = isValidIconUrl(iconUrl)
    var imageLoaded by remember(iconUrl) { mutableStateOf(false) }
    var retryCount by remember(iconUrl) { mutableIntStateOf(0) }
    var loadError by remember(iconUrl) { mutableStateOf(false) }
    LaunchedEffect(loadError, retryCount) {
        if (loadError && !imageLoaded) {
            val backoffMs = minOf(3_000L * (1 shl minOf(retryCount, 7)), 5 * 60_000L)
            println("[RelayHeaderIcon] will retry $iconUrl in ${backoffMs}ms (attempt ${retryCount + 2})")
            delay(backoffMs)
            retryCount++
            loadError = false
        }
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(if (imageLoaded && hasIcon) NostrordColors.BackgroundDark else generateColorFromString(relayUrl)),
        contentAlignment = Alignment.Center
    ) {
        // Base layer: fallback shown until image overlays it
        if (fallbackPainter != null) {
            androidx.compose.foundation.Image(
                painter = fallbackPainter,
                contentDescription = label,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop
            )
        } else if (!imageLoaded) {
            Text(
                text = label.take(1).uppercase(),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (hasIcon) {
            key(retryCount) {
                AsyncImage(
                    model = buildRelayIconRequest(iconUrl!!, context),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Success -> {
                                imageLoaded = true
                                loadError = false
                                println("[RelayHeaderIcon] loaded $iconUrl (attempt ${retryCount + 1})")
                            }
                            is AsyncImagePainter.State.Error -> {
                                imageLoaded = false
                                loadError = true
                                println("[RelayHeaderIcon] error $iconUrl attempt=${retryCount + 1}: ${state.result.throwable?.message}")
                            }
                            else -> {}
                        }
                    }
                )
            }
        }
    }
}
