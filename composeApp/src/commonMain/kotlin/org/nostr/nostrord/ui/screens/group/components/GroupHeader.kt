package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.People
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
import org.nostr.nostrord.ui.util.buildShareGroupLink
import org.nostr.nostrord.ui.util.generateColorFromString
import org.nostr.nostrord.utils.rememberClipboardWriter

/** Group header with avatar, name, join/invite actions, and admin menu. */
@Composable
fun GroupHeader(
    groupName: String?,
    groupMetadata: GroupMetadata?,
    relayUrl: String = "",
    groupId: String = "",
    isJoined: Boolean,
    onJoinClick: (inviteCode: String?) -> Unit,
    onLeaveClick: () -> Unit,
    onTitleClick: () -> Unit = {},
    onEditClick: () -> Unit = {},
    onDeleteClick: () -> Unit = {},
    onManageMembersClick: () -> Unit = {},
    onInviteCodesClick: () -> Unit = {},
    onCreateSubgroupClick: () -> Unit = {},
    onManageChildrenClick: () -> Unit = {},
    showSubgroupControls: Boolean = true,
    parentGroupName: String? = null,
    onParentClick: () -> Unit = {},
    childCount: Int = 0,
    isAdmin: Boolean = false,
    isClosed: Boolean = false,
    initialInviteCode: String? = null,
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
            if (navigationIcon != null) {
                navigationIcon()
            }

            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClick = onTitleClick)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .padding(end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                GroupHeaderIcon(
                    pictureUrl = groupMetadata?.picture,
                    groupId = groupMetadata?.id ?: "",
                    displayName = groupName ?: "Group",
                    size = 36.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    if (parentGroupName != null) {
                        Text(
                            text = "◀ $parentGroupName",
                            color = NostrordColors.TextSecondary,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .clickable(onClick = onParentClick)
                                .pointerHoverIcon(PointerIcon.Hand)
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = groupMetadata?.name ?: groupName ?: "Unknown Group",
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        if (childCount > 0) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(NostrordColors.SurfaceVariant, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = "› $childCount",
                                    color = NostrordColors.TextSecondary,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

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

            if (trailingIcon != null) {
                trailingIcon()
            }

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

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!isJoined) {
                    val showInviteButton = isClosed || initialInviteCode != null
                    var showInviteModal by remember { mutableStateOf(initialInviteCode != null) }

                    if (showInviteButton) {
                        Button(
                            onClick = { showInviteModal = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NostrordColors.SurfaceVariant,
                                contentColor = NostrordColors.TextPrimary
                            )
                        ) {
                            Icon(
                                Icons.Default.VpnKey,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Invite Code", style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }

                    Button(
                        onClick = { onJoinClick(null) },
                        colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("Join", style = MaterialTheme.typography.labelMedium)
                    }

                    if (showInviteModal) {
                        InviteCodeJoinModal(
                            initialCode = initialInviteCode ?: "",
                            onJoin = { code ->
                                showInviteModal = false
                                onJoinClick(code)
                            },
                            onDismiss = { showInviteModal = false }
                        )
                    }
                } else {
                    var menuExpanded by remember { mutableStateOf(false) }
                    val copyToClipboard = rememberClipboardWriter()

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
                                    text = { Text("Manage Members", color = NostrordColors.TextPrimary) },
                                    onClick = {
                                        menuExpanded = false
                                        onManageMembersClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.People,
                                            contentDescription = null,
                                            tint = NostrordColors.TextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                if (isClosed) {
                                    DropdownMenuItem(
                                        text = { Text("Invite Codes", color = NostrordColors.TextPrimary) },
                                        onClick = {
                                            menuExpanded = false
                                            onInviteCodesClick()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.Link,
                                                contentDescription = null,
                                                tint = NostrordColors.TextSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    )
                                }
                                if (showSubgroupControls) {
                                    DropdownMenuItem(
                                    text = { Text("Create Subgroup", color = NostrordColors.TextPrimary) },
                                    onClick = {
                                        menuExpanded = false
                                        onCreateSubgroupClick()
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Add,
                                            contentDescription = null,
                                            tint = NostrordColors.TextSecondary,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                )
                                    DropdownMenuItem(
                                        text = { Text("Manage Children", color = NostrordColors.TextPrimary) },
                                        onClick = {
                                            menuExpanded = false
                                            onManageChildrenClick()
                                        },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.AccountTree,
                                                contentDescription = null,
                                                tint = NostrordColors.TextSecondary,
                                                modifier = Modifier.size(20.dp)
                                            )
                                        }
                                    )
                                }
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
                            if (relayUrl.isNotBlank() && groupId.isNotBlank()) {
                                DropdownMenuItem(
                                    text = { Text("Share", color = NostrordColors.TextPrimary) },
                                    onClick = {
                                        menuExpanded = false
                                        copyToClipboard(buildShareGroupLink(relayUrl, groupId))
                                    },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Default.Share,
                                            contentDescription = null,
                                            tint = NostrordColors.TextSecondary,
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

/** Modal for entering an invite code to join a closed group. */
@Composable
fun InviteCodeJoinModal(
    initialCode: String = "",
    onJoin: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var inviteCode by remember { mutableStateOf(initialCode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NostrordColors.Surface,
        title = {
            Text(
                "Join with Invite Code",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            OutlinedTextField(
                value = inviteCode,
                onValueChange = { inviteCode = it.trim() },
                placeholder = {
                    Text(
                        "Enter invite code",
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    focusedBorderColor = NostrordColors.Primary,
                    unfocusedBorderColor = NostrordColors.SurfaceVariant,
                    cursorColor = NostrordColors.Primary
                ),
                textStyle = MaterialTheme.typography.bodyMedium,
                shape = RoundedCornerShape(8.dp)
            )
        },
        confirmButton = {
            Button(
                onClick = { onJoin(inviteCode) },
                enabled = inviteCode.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = NostrordColors.Primary,
                    contentColor = Color.White,
                    disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.3f),
                    disabledContentColor = Color.White.copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Join")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = NostrordColors.TextSecondary)
            }
        }
    )
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
