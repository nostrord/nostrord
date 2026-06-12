package org.nostr.nostrord.ui.screens.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.notifications.NotificationEntry
import org.nostr.nostrord.notifications.NotificationType
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.navigation.relayShortLabel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.rememberEmojiFontFamily
import org.nostr.nostrord.ui.util.generateColorFromString
import org.nostr.nostrord.ui.util.relayFallbackPainter
import org.nostr.nostrord.utils.formatTimestamp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsScreen(
    onNavigate: (Screen) -> Unit,
    onOpenGroupAtRelay: (groupId: String, groupName: String?, relayUrl: String, targetMessageId: String?) -> Unit,
    onOpenDrawer: (() -> Unit)? = null,
) {
    val vm = viewModel { NotificationsViewModel(AppModule.nostrRepository) }
    val entries by vm.entries.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()
    // Cross-relay group lookup. The `groups` flow is scoped to the active relay,
    // so notifications from background relays would fall back to a truncated id.
    val groupsByRelay by vm.groupsByRelay.collectAsState()
    val relayMetadata by vm.relayMetadata.collectAsState()

    val hasUnread = remember(entries) { entries.any { !it.read } }
    var overflowOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications", style = NostrordTypography.ServerHeader, color = Color.White) },
                navigationIcon = {
                    // Mobile-only hamburger to reopen the drawer. On desktop the
                    // bell lives in the server rail, so no navigation icon here —
                    // users dismiss the screen by clicking elsewhere in the rail.
                    if (onOpenDrawer != null) {
                        IconButton(onClick = onOpenDrawer) {
                            Icon(
                                Icons.Default.Menu,
                                contentDescription = "Open sidebar",
                                tint = NostrordColors.TextSecondary,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { vm.markAllRead() },
                        enabled = hasUnread,
                    ) {
                        Icon(
                            Icons.Default.DoneAll,
                            contentDescription = "Mark all as read",
                            tint =
                            if (hasUnread) {
                                NostrordColors.TextSecondary
                            } else {
                                NostrordColors.TextMuted.copy(alpha = 0.4f)
                            },
                        )
                    }
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = NostrordColors.TextSecondary,
                            )
                        }
                        DropdownMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                            containerColor = NostrordColors.Surface,
                        ) {
                            DropdownMenuItem(
                                text = { Text("Clear all", color = NostrordColors.TextPrimary) },
                                enabled = entries.isNotEmpty(),
                                onClick = {
                                    overflowOpen = false
                                    vm.clearHistory()
                                },
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = NostrordColors.BackgroundDark),
            )
        },
        containerColor = NostrordColors.Background,
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Default.Notifications,
                            contentDescription = null,
                            tint = NostrordColors.TextMuted,
                            modifier = Modifier.size(48.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Text("No notifications yet", color = NostrordColors.TextMuted, fontSize = 15.sp)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        val meta = userMetadata[entry.actorPubkey]
                        val authorName =
                            meta?.displayName?.takeIf { it.isNotBlank() }
                                ?: meta?.name?.takeIf { it.isNotBlank() }
                                ?: (entry.actorPubkey.take(8) + "…")
                        // Prefer the entry's own relay bucket; fall back to any relay
                        // that knows the group (joined groups can appear on multiple
                        // mirrored relays). Last resort is the truncated id.
                        val groupMeta =
                            groupsByRelay[entry.relayUrl]?.firstOrNull { it.id == entry.groupId }
                                ?: groupsByRelay.values.firstNotNullOfOrNull { list ->
                                    list.firstOrNull { it.id == entry.groupId }
                                }
                        // Prefer the snapshot captured at notification time. Live cache
                        // lookup is the secondary path for legacy entries that predate
                        // snapshotting. Truncated id is the last resort.
                        val groupName =
                            entry.groupName?.takeIf { it.isNotBlank() }
                                ?: groupMeta?.name?.takeIf { it.isNotBlank() }
                                ?: entry.groupId.take(8)
                        val relayInfo = relayMetadata[entry.relayUrl]
                        val relayName =
                            entry.relayName?.takeIf { it.isNotBlank() }
                                ?: relayInfo?.name?.takeIf { it.isNotBlank() }
                                ?: entry.relayUrl.takeIf { it.isNotBlank() }?.let { relayShortLabel(it) }
                                ?: ""

                        NotificationItem(
                            entry = entry,
                            authorName = authorName,
                            avatarUrl = meta?.picture,
                            groupName = groupName,
                            groupPicture = groupMeta?.picture,
                            relayName = relayName,
                            relayIconUrl = relayInfo?.icon,
                            onClick = {
                                vm.markRead(entry.id)
                                onOpenGroupAtRelay(
                                    entry.groupId,
                                    groupName,
                                    entry.relayUrl,
                                    entry.messageId,
                                )
                            },
                        )
                        HorizontalDivider(color = NostrordColors.BackgroundDark, thickness = 1.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationItem(
    entry: NotificationEntry,
    authorName: String,
    avatarUrl: String?,
    groupName: String,
    groupPicture: String?,
    relayName: String,
    relayIconUrl: String?,
    onClick: () -> Unit,
) {
    // NotoColorEmoji loaded from app resources. Skia (desktop/web) has no
    // system color-emoji font, so without this the badge glyph and reaction
    // preview fall back to monochrome tofu.
    val emojiFontFamily = rememberEmojiFontFamily()
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (!entry.read) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                } else {
                    Color.Transparent
                },
            ).height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top,
    ) {
        // Left accent bar — primary-color stripe on unread, transparent spacer
        // on read entries so content alignment stays stable across states.
        Box(
            modifier =
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(
                    if (!entry.read) MaterialTheme.colorScheme.primary else Color.Transparent,
                ),
        )

        Row(
            modifier =
            Modifier
                .weight(1f)
                .padding(start = 13.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box {
                OptimizedSmallAvatar(
                    imageUrl = avatarUrl,
                    identifier = entry.actorPubkey,
                    displayName = authorName,
                    size = 40.dp,
                )
                Box(
                    modifier =
                    Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                        .clip(CircleShape)
                        .background(notificationTypeColor(entry.type)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = notificationTypeEmoji(entry),
                        fontSize = 8.sp,
                        lineHeight = 8.sp,
                        fontFamily = emojiFontFamily,
                    )
                }
            }

            Column(modifier = Modifier.weight(1f)) {
                // Author + action in a single AnnotatedString so the action label
                // wraps to a second line instead of clipping off-screen on narrow
                // viewports (e.g. Android compact). The previous two-Text Row had
                // the label without weight or ellipsis, and "reacted to your
                // message" went out of bounds before users could see it.
                val header =
                    buildAnnotatedString {
                        withStyle(
                            SpanStyle(
                                fontWeight = FontWeight.SemiBold,
                                color = NostrordColors.TextPrimary,
                            ),
                        ) { append(authorName) }
                        append(' ')
                        withStyle(SpanStyle(color = NostrordColors.TextMuted)) {
                            append(notificationTypeLabel(entry.type))
                        }
                    }
                Text(
                    text = header,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                Spacer(Modifier.height(2.dp))

                val isReaction = entry.type == NotificationType.REACTION
                Text(
                    text = if (isReaction) entry.emoji ?: entry.preview else entry.preview,
                    color = NostrordColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    // Only swap the family when the body IS the emoji; for regular
                    // message previews we keep system text rendering.
                    fontFamily = if (isReaction) emojiFontFamily else null,
                )

                Spacer(Modifier.height(4.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ContextIcon(
                        imageUrl = groupPicture,
                        identifier = entry.groupId,
                        label = groupName,
                        size = 16.dp,
                    )
                    Text(
                        text = groupName,
                        color = NostrordColors.TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (relayName.isNotBlank()) {
                        Text("·", color = NostrordColors.TextMuted, fontSize = 12.sp)
                        ContextIcon(
                            imageUrl = relayIconUrl,
                            identifier = entry.relayUrl,
                            label = relayName,
                            size = 16.dp,
                            bundledFallback = relayFallbackPainter(entry.relayUrl),
                        )
                        Text(
                            text = relayName,
                            color = NostrordColors.TextMuted,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    Text("·", color = NostrordColors.TextMuted, fontSize = 12.sp)
                    Text(
                        text = formatTimestamp(entry.createdAt),
                        color = NostrordColors.TextMuted,
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

private fun notificationTypeLabel(type: NotificationType): String = when (type) {
    NotificationType.REPLY -> "replied to your message"
    NotificationType.MENTION -> "mentioned you"
    NotificationType.REACTION -> "reacted to your message"
    NotificationType.MESSAGE -> "sent a message"
}

private fun notificationTypeEmoji(entry: NotificationEntry): String = when (entry.type) {
    NotificationType.REPLY -> "↩"
    NotificationType.MENTION -> "@"
    NotificationType.REACTION -> entry.emoji?.take(2) ?: "+"
    NotificationType.MESSAGE -> "💬"
}

private fun notificationTypeColor(type: NotificationType): Color = when (type) {
    NotificationType.REPLY -> Color(0xFF5B8AF5)
    NotificationType.MENTION -> Color(0xFFE67E22)
    NotificationType.REACTION -> Color(0xFFE74C3C)
    NotificationType.MESSAGE -> Color(0xFF27AE60)
}

/**
 * Small rounded square icon for groups and relays in the notification feed.
 * Loads the image whenever a URL is provided; falls back to a colored square with
 * the first letter of the label. Doesn't use [OptimizedSmallAvatar] because at
 * sizes below 24dp that helper skips image loading and only shows its gradient
 * fallback — hiding real pictures.
 */
@Composable
private fun ContextIcon(
    imageUrl: String?,
    identifier: String,
    label: String,
    size: Dp,
    bundledFallback: androidx.compose.ui.graphics.painter.Painter? = null,
) {
    val context = LocalPlatformContext.current
    val shape = RoundedCornerShape(4.dp)
    var imageState by remember(imageUrl) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    val remoteOk = !imageUrl.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error
    // Mirrors ServerRail: bundled painter only kicks in when the relay has no
    // NIP-11 icon at all. A remote URL that fails still falls through to the
    // letter glyph so the issue stays visible rather than masked by an asset.
    val showBundled = imageUrl.isNullOrBlank() && bundledFallback != null
    val showLetter = !remoteOk && !showBundled

    Box(
        modifier =
        Modifier
            .size(size)
            .clip(shape)
            .background(if (showLetter) generateColorFromString(identifier) else NostrordColors.BackgroundDark),
        contentAlignment = Alignment.Center,
    ) {
        if (showLetter) {
            val glyphSp = (size.value * 0.55f).sp
            Text(
                text = label.take(1).uppercase(),
                color = Color.White,
                fontSize = glyphSp,
                lineHeight = glyphSp,
                fontWeight = FontWeight.Bold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (showBundled && bundledFallback != null) {
            androidx.compose.foundation.Image(
                painter = bundledFallback,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier =
                Modifier
                    .fillMaxSize()
                    .clip(shape),
            )
        }
        if (!imageUrl.isNullOrBlank()) {
            AsyncImage(
                model =
                ImageRequest
                    .Builder(context)
                    .data(imageUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = label,
                modifier =
                Modifier
                    .fillMaxSize()
                    .clip(shape),
                contentScale = ContentScale.Crop,
                onState = { imageState = it },
            )
        }
    }
}
