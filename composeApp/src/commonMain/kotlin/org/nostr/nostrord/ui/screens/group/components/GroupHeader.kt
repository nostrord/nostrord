package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.generateColorFromString

/**
 * Enhanced group header with description, member count, and status indicators.
 * Click on the title area to open group info modal.
 */
@Composable
fun GroupHeader(
    groupName: String?,
    groupMetadata: GroupMetadata?,
    isJoined: Boolean,
    onJoinClick: () -> Unit,
    onLeaveClick: () -> Unit,
    onTitleClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    isAdmin: Boolean = false,
    pendingJoinRequestCount: Int = 0,
    onJoinRequestsClick: () -> Unit = {},
    modifier: Modifier = Modifier,
    navigationIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(NostrordColors.BackgroundDark)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Navigation icons
            if (navigationIcon != null) {
                navigationIcon()
            }

            // Clickable title area (avatar + name + description)
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onTitleClick)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Group avatar — matches sidebar/card style
                GroupHeaderIcon(
                    pictureUrl = groupMetadata?.picture,
                    groupId = groupMetadata?.id ?: "",
                    displayName = groupName ?: "Group",
                    size = 36.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                // Group name and description
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupMetadata?.name ?: groupName ?: "Unknown Group",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Description
                    if (!groupMetadata?.about.isNullOrBlank()) {
                        Text(
                            text = groupMetadata?.about ?: "",
                            color = NostrordColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Trailing icon (e.g. members button when sidebar is hidden)
            if (trailingIcon != null) {
                trailingIcon()
            }

            // Join requests badge (admin only)
            if (isAdmin && pendingJoinRequestCount > 0) {
                Box {
                    IconButton(
                        onClick = onJoinRequestsClick,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.PersonAdd,
                            contentDescription = "Join requests",
                            tint = NostrordColors.TextSecondary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    // Count badge
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = (-4).dp, y = 4.dp)
                            .size(18.dp)
                            .background(NostrordColors.Error, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (pendingJoinRequestCount > 9) "9+" else pendingJoinRequestCount.toString(),
                            color = Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isJoined) {
                    // Join button for non-members
                    Button(
                        onClick = onJoinClick,
                        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text("Join", style = MaterialTheme.typography.labelMedium)
                    }
                } else {
                    // Dropdown menu for members
                    var menuExpanded by remember { mutableStateOf(false) }

                    Box {
                        IconButton(
                            onClick = { menuExpanded = true },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                Icons.Default.MoreVert,
                                contentDescription = "More options",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false },
                            containerColor = NostrordColors.Surface,
                        ) {
                            if (isAdmin) {
                                DropdownMenuItem(
                                    text = { Text("Edit Group", color = NostrordColors.TextPrimary) },
                                    onClick = {
                                        menuExpanded = false
                                        onEditClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Edit,
                                            contentDescription = null,
                                            tint = NostrordColors.TextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete Group", color = NostrordColors.Error) },
                                    onClick = {
                                        menuExpanded = false
                                        onDeleteClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = null,
                                            tint = NostrordColors.Error,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Leave Group",
                                        color = NostrordColors.Error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    onLeaveClick()
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ExitToApp,
                                        contentDescription = null,
                                        tint = NostrordColors.Error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun GroupHeaderIcon(
    pictureUrl: String?,
    groupId: String,
    displayName: String,
    size: androidx.compose.ui.unit.Dp
) {
    val context = LocalPlatformContext.current
    val iconShape = RoundedCornerShape(8.dp)
    var imageState by remember(pictureUrl) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    val showImage = !pictureUrl.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error

    Box(
        modifier = Modifier
            .size(size)
            .clip(iconShape)
            .background(if (!showImage) generateColorFromString(groupId) else NostrordColors.BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        if (!showImage) {
            Text(
                text = displayName.take(1).uppercase(),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (!pictureUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(pictureUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = displayName,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(iconShape),
                contentScale = ContentScale.Crop,
                onState = { imageState = it }
            )
        }
    }
}
