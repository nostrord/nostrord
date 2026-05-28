package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.generateColorFromString

@Composable
fun ManageRelayContent(
    relayUrl: String,
    groups: List<GroupMetadata>,
    isCompact: Boolean,
    onForgetGroup: (groupId: String) -> Unit,
    onRemoveRelay: () -> Unit,
    relayMeta: Nip11RelayInfo? = null,
    isOffline: Boolean = false,
    isRelaySaved: Boolean = true,
    onDismiss: (() -> Unit)? = null,
    dismissAtBottom: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val domain = relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
    val relayName = relayMeta?.name?.takeIf { it.isNotBlank() } ?: domain

    var confirmRemoveRelay by remember { mutableStateOf(false) }

    if (confirmRemoveRelay) {
        AlertDialog(
            onDismissRequest = { confirmRemoveRelay = false },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Remove relay?") },
            text = {
                Text(
                    if (isRelaySaved) {
                        if (groups.isEmpty()) {
                            "$relayName will be removed from your relay list."
                        } else {
                            "$relayName and all its groups will be removed from your list."
                        }
                    } else {
                        "Your groups on $relayName will be removed from your list."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemoveRelay = false
                    onRemoveRelay()
                }) {
                    Text("Remove", color = NostrordColors.Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemoveRelay = false }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            },
        )
    }

    LazyColumn(
        modifier =
        modifier
            .fillMaxSize()
            .background(NostrordColors.BackgroundDark),
        contentPadding =
        PaddingValues(
            horizontal = if (isCompact) 16.dp else 24.dp,
            vertical = if (isCompact) 16.dp else 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            RelayManagementHeader(
                relayUrl = relayUrl,
                relayName = relayName,
                relayMeta = relayMeta,
                isCompact = isCompact,
                isOffline = isOffline,
                onDismiss = if (dismissAtBottom) null else onDismiss,
            )
        }

        if (groups.isEmpty()) {
            item {
                Text(
                    text = "No groups joined on this relay.",
                    color = NostrordColors.TextMuted,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        } else {
            item {
                Text(
                    text = "My groups",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(groups, key = { it.id }) { group ->
                OfflineGroupRow(
                    group = group,
                    isCompact = isCompact,
                    onForget = { onForgetGroup(group.id) },
                )
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            if (dismissAtBottom && onDismiss != null) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Back", color = NostrordColors.TextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = { confirmRemoveRelay = true },
                        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Remove relay", color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            } else {
                Button(
                    onClick = { confirmRemoveRelay = true },
                    colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = "Remove relay",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}

@Composable
private fun RelayManagementHeader(
    relayUrl: String,
    relayName: String,
    relayMeta: Nip11RelayInfo?,
    isCompact: Boolean,
    isOffline: Boolean,
    onDismiss: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (isCompact) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (onDismiss != null) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                            tint = NostrordColors.TextSecondary,
                        )
                    }
                }
                Text(
                    text = "Manage relay",
                    color = NostrordColors.TextPrimary,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = NostrordColors.TextSecondary,
                    )
                }
            }
            Text(
                text = "Manage relay",
                color = NostrordColors.TextPrimary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RelayHeaderIcon(
                    relayUrl = relayUrl,
                    iconUrl = relayMeta?.icon,
                    label = relayName,
                    size = 56.dp,
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = relayName,
                        color = NostrordColors.TextPrimary,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (isOffline) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Default.WifiOff,
                                contentDescription = null,
                                tint = NostrordColors.StatusOffline,
                                modifier = Modifier.size(14.dp),
                            )
                            Text(
                                text = "Offline",
                                color = NostrordColors.StatusOffline,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineGroupRow(
    group: GroupMetadata,
    isCompact: Boolean,
    onForget: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }

    if (showConfirm) {
        val groupLabel = group.name ?: group.id
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            containerColor = NostrordColors.Surface,
            titleContentColor = NostrordColors.TextPrimary,
            textContentColor = NostrordColors.TextSecondary,
            title = { Text("Leave group?") },
            text = { Text("You will be removed from \"$groupLabel\".") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onForget()
                }) {
                    Text("Leave", color = NostrordColors.Error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            },
        )
    }

    Surface(
        color = NostrordColors.Surface,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupIcon(
                group = group,
                size = if (isCompact) 36.dp else 40.dp,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                if (group.name != null) {
                    Text(
                        text = group.name,
                        color = NostrordColors.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = group.id,
                    color = if (group.name != null) NostrordColors.TextMuted else NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = { showConfirm = true },
                modifier = Modifier.size(32.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Leave group",
                    tint = NostrordColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
    }
}

@Composable
private fun GroupIcon(
    group: GroupMetadata,
    size: Dp,
) {
    val context = LocalPlatformContext.current
    val pictureUrl = group.picture
    var imageState by remember(pictureUrl) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    val showImage = !pictureUrl.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error
    val displayName = group.name ?: group.id
    val shape = RoundedCornerShape(8.dp)

    Box(
        modifier =
        Modifier
            .size(size)
            .clip(shape)
            .background(if (!showImage) generateColorFromString(group.id) else NostrordColors.BackgroundDark),
        contentAlignment = Alignment.Center,
    ) {
        if (!showImage) {
            Text(
                text = displayName.take(1).uppercase(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (!pictureUrl.isNullOrBlank()) {
            AsyncImage(
                model =
                ImageRequest
                    .Builder(context)
                    .data(pictureUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = displayName,
                modifier = Modifier.fillMaxSize().clip(shape),
                contentScale = ContentScale.Crop,
                onState = { imageState = it },
            )
        }
    }
}
