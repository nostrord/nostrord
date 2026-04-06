package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
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
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.isValidIconUrl
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.loading.ConnectionErrorState
import org.nostr.nostrord.ui.components.loading.GroupCardSkeleton
import org.nostr.nostrord.ui.components.loading.RestrictedRelayState
import org.nostr.nostrord.ui.components.navigation.relayShortLabel
import org.nostr.nostrord.ui.screens.home.components.PickGroupCard
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.buildRelayIconRequest
import org.nostr.nostrord.ui.util.relayFallbackPainter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreenMobile(
    gridState: LazyGridState,
    onNavigate: (Screen) -> Unit,
    joinedGroups: Set<String>,
    filteredGroups: List<GroupMetadata>,
    groupCount: Int = 0,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    activeFilter: GroupFilter,
    onFilterChange: (GroupFilter) -> Unit,
    currentRelayUrl: String,
    relayMeta: Nip11RelayInfo? = null,
    isLoading: Boolean = false,
    hasError: Boolean = false,
    errorMessage: String? = null,
    onRetry: () -> Unit = {},
    onCreateGroupClick: () -> Unit = {},
    onOpenDrawer: () -> Unit = {},
    onRemoveRelay: () -> Unit = {},
    onAddRelay: () -> Unit = {},
    isRelaySaved: Boolean = true
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val context = LocalPlatformContext.current
                    val iconUrl = relayMeta?.icon
                    val hasValidIcon = isValidIconUrl(iconUrl)
                    val fallbackPainter = relayFallbackPainter(currentRelayUrl)

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Relay icon (NIP-11 icon, bundled fallback, or first-letter)
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(NostrordColors.Surface),
                            contentAlignment = Alignment.Center
                        ) {
                            var imageLoaded by remember(iconUrl, currentRelayUrl) { mutableStateOf(false) }

                            // Fallback: bundled painter or first letter
                            if (!imageLoaded) {
                                if (fallbackPainter != null) {
                                    androidx.compose.foundation.Image(
                                        painter = fallbackPainter,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                } else {
                                    val label = relayMeta?.name?.takeIf { it.isNotBlank() }
                                        ?: relayShortLabel(currentRelayUrl)
                                    Text(
                                        text = label.take(1).uppercase(),
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            // NIP-11 icon (loads over fallback)
                            if (hasValidIcon) {
                                AsyncImage(
                                    model = buildRelayIconRequest(iconUrl!!, context),
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                                    onState = { state ->
                                        if (state is AsyncImagePainter.State.Success) {
                                            imageLoaded = true
                                        }
                                    }
                                )
                            }
                        }

                        Text(
                            relayMeta?.name?.takeIf { it.isNotBlank() } ?: relayShortLabel(currentRelayUrl),
                            style = NostrordTypography.ServerHeader,
                            color = Color.White
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.size(Spacing.touchTargetMin)
                    ) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = "Open sidebar",
                            tint = NostrordColors.TextSecondary
                        )
                    }
                },
                actions = {
                    RelayOptionsMenu(
                        relayUrl = currentRelayUrl,
                        isRelaySaved = isRelaySaved,
                        onAddRelay = onAddRelay,
                        onRemoveRelay = onRemoveRelay
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = NostrordColors.BackgroundDark
                )
            )
        },
        containerColor = NostrordColors.Background
    ) { paddingValues ->
        when {
            hasError -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    if (errorMessage != null && errorMessage.contains("restricted")) {
                        RestrictedRelayState(message = errorMessage)
                    } else {
                        ConnectionErrorState(onRetry = onRetry)
                    }
                }
            }
            isLoading && filteredGroups.isEmpty() -> {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(1),
                    modifier = Modifier.fillMaxSize().padding(paddingValues),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(4) { GroupCardSkeleton() }
                }
            }
            else -> {
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(1),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .background(NostrordColors.Background),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Filter bar
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MobileFilterBar(
                            activeFilter = activeFilter,
                            onFilterChange = onFilterChange,
                            allCount = groupCount,
                            joinedCount = joinedGroups.size
                        )
                    }

                    // Search input
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        MobileSearch(query = searchQuery, onQueryChange = onSearchChange)
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
            }
        }
    }
}

@Composable
private fun MobileFilterBar(
    activeFilter: GroupFilter,
    onFilterChange: (GroupFilter) -> Unit,
    allCount: Int = 0,
    joinedCount: Int = 0
) {
    val filters = listOf(
        GroupFilter.All to if (allCount > 0) "All ($allCount)" else "All",
        GroupFilter.Joined to if (joinedCount > 0) "Joined ($joinedCount)" else "Joined"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 2.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        filters.forEach { (filter, label) ->
            MobileFilterChip(
                label = label,
                isActive = activeFilter == filter,
                onClick = { onFilterChange(filter) }
            )
        }
    }
}

@Composable
private fun MobileFilterChip(label: String, isActive: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val bg = when {
        isActive -> NostrordColors.Primary
        isHovered -> NostrordColors.SurfaceVariant
        else -> NostrordColors.BackgroundDark
    }
    val textColor = if (isActive) Color.White else NostrordColors.TextMuted

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
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
private fun MobileSearch(query: String, onQueryChange: (String) -> Unit) {
    val focusRequester = remember { FocusRequester() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 4.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(NostrordColors.BackgroundDark)
            .height(40.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { focusRequester.requestFocus() }
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .onPreviewKeyEvent { event ->
                            if (event.key == Key.Escape && event.type == KeyEventType.KeyDown && query.isNotEmpty()) {
                                onQueryChange("")
                                true
                            } else false
                        }
                )
            }
            if (query.isNotEmpty()) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier.size(20.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear search",
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}
