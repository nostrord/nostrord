package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.avatars.Jdenticon
import org.nostr.nostrord.ui.screens.group.model.MemberInfo
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

private enum class MemberFilter(val label: String) {
    ALL("All"),
    ADMINS("Admins"),
    MEMBERS("Members")
}

/**
 * Modal for managing group members: promote/demote admin, remove members.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemberManagementModal(
    members: List<MemberInfo>,
    currentUserPubkey: String?,
    onPromoteToAdmin: (String) -> Unit,
    onDemoteFromAdmin: (String) -> Unit,
    onRemoveMember: (MemberInfo) -> Unit,
    onDismiss: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf(MemberFilter.ALL) }
    var memberToConfirmAction by remember { mutableStateOf<Pair<MemberInfo, String>?>(null) }

    val filteredMembers = remember(members, searchQuery, selectedFilter) {
        val searched = if (searchQuery.isBlank()) members else {
            val query = searchQuery.lowercase()
            members.filter { member ->
                member.displayName.lowercase().contains(query) ||
                    member.pubkey.lowercase().contains(query) ||
                    Nip19.encodeNpub(member.pubkey).lowercase().contains(query)
            }
        }
        when (selectedFilter) {
            MemberFilter.ALL -> searched
            MemberFilter.ADMINS -> searched.filter { it.isAdmin }
            MemberFilter.MEMBERS -> searched.filter { !it.isAdmin }
        }
    }

    val adminCount = remember(members) { members.count { it.isAdmin } }
    val memberCount = remember(members) { members.count { !it.isAdmin } }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDismiss() }
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(0.9f)
                    .heightIn(max = 600.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
            ) {
                Column(modifier = Modifier.padding(Spacing.xxl)) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Manage Members",
                                style = NostrordTypography.ServerHeader,
                                color = NostrordColors.TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text(
                                text = "Promote, demote, or remove group members.",
                                style = NostrordTypography.Caption,
                                color = NostrordColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Stats row
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        MemberStatBadge(
                            label = "Total",
                            count = members.size,
                            color = NostrordColors.TextSecondary
                        )
                        MemberStatBadge(
                            label = "Admins",
                            count = adminCount,
                            color = NostrordColors.Primary
                        )
                        MemberStatBadge(
                            label = "Members",
                            count = memberCount,
                            color = NostrordColors.Success
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // Filter chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        MemberFilter.entries.forEach { filter ->
                            val isSelected = selectedFilter == filter
                            Surface(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { selectedFilter = filter }
                                    .pointerHoverIcon(PointerIcon.Hand),
                                shape = RoundedCornerShape(16.dp),
                                color = if (isSelected) NostrordColors.Primary.copy(alpha = 0.15f) else NostrordColors.SurfaceVariant
                            ) {
                                Text(
                                    text = filter.label,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) NostrordColors.Primary else NostrordColors.TextMuted,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // Search field
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = {
                            Text(
                                "Search members...",
                                color = NostrordColors.TextMuted,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = null,
                                tint = NostrordColors.TextMuted,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = NostrordColors.TextPrimary,
                            unfocusedTextColor = NostrordColors.TextPrimary,
                            focusedBorderColor = NostrordColors.Primary,
                            unfocusedBorderColor = NostrordColors.Divider,
                            cursorColor = NostrordColors.Primary,
                            focusedContainerColor = NostrordColors.InputBackground,
                            unfocusedContainerColor = NostrordColors.InputBackground,
                            focusedPlaceholderColor = NostrordColors.TextMuted,
                            unfocusedPlaceholderColor = NostrordColors.TextMuted
                        ),
                        shape = RoundedCornerShape(8.dp),
                        textStyle = MaterialTheme.typography.bodySmall
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // Member list
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        items(filteredMembers, key = { it.pubkey }) { member ->
                            AdminMemberItem(
                                member = member,
                                isSelf = member.pubkey == currentUserPubkey,
                                onPromote = { memberToConfirmAction = member to "promote" },
                                onDemote = { memberToConfirmAction = member to "demote" },
                                onRemove = { memberToConfirmAction = member to "remove" }
                            )
                        }

                        if (filteredMembers.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxWidth().padding(Spacing.xxl),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (searchQuery.isNotBlank()) "No members found" else "No members",
                                        color = NostrordColors.TextMuted,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Confirmation dialog
    memberToConfirmAction?.let { (member, action) ->
        val actionLabel = when (action) {
            "promote" -> "Promote to Admin"
            "demote" -> "Remove Admin Role"
            "remove" -> "Remove from Group"
            else -> action
        }
        val actionDesc = when (action) {
            "promote" -> "${member.displayName} will be able to manage members and group settings."
            "demote" -> "${member.displayName} will lose admin privileges."
            "remove" -> "${member.displayName} will be removed from the group."
            else -> ""
        }

        AlertDialog(
            onDismissRequest = { memberToConfirmAction = null },
            title = {
                Text(
                    text = actionLabel,
                    color = NostrordColors.TextPrimary,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = actionDesc,
                    color = NostrordColors.TextSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        when (action) {
                            "promote" -> onPromoteToAdmin(member.pubkey)
                            "demote" -> onDemoteFromAdmin(member.pubkey)
                            "remove" -> onRemoveMember(member)
                        }
                        memberToConfirmAction = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (action == "remove") NostrordColors.Error else NostrordColors.Primary,
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { memberToConfirmAction = null }) {
                    Text("Cancel", color = NostrordColors.TextSecondary)
                }
            },
            containerColor = NostrordColors.Surface,
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
private fun MemberStatBadge(label: String, count: Int, color: Color) {
    Row(
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text(
            text = "$count",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color.copy(alpha = 0.7f)
        )
    }
}

@Composable
private fun AdminMemberItem(
    member: MemberInfo,
    isSelf: Boolean,
    onPromote: () -> Unit,
    onDemote: () -> Unit,
    onRemove: () -> Unit
) {
    var showRoleMenu by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.SurfaceVariant.copy(alpha = 0.5f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Avatar
        AdminMemberAvatar(member = member, size = 36.dp)

        Spacer(modifier = Modifier.width(12.dp))

        // Name + npub
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = member.displayName,
                    color = NostrordColors.TextPrimary,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isSelf) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "YOU",
                        color = NostrordColors.TextMuted,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier
                            .background(
                                NostrordColors.TextMuted.copy(alpha = 0.15f),
                                RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 1.dp)
                    )
                }
            }
            Text(
                text = Nip19.encodeNpub(member.pubkey).take(20) + "...",
                color = NostrordColors.TextMuted,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }

        // Role badge
        if (member.isAdmin) {
            Text(
                text = "ADMIN",
                color = NostrordColors.Primary,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .background(
                        NostrordColors.Primary.copy(alpha = 0.15f),
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
        }

        // Role action dropdown (not for self)
        if (!isSelf) {
            Box {
                IconButton(
                    onClick = { showRoleMenu = true },
                    modifier = Modifier.size(32.dp).pointerHoverIcon(PointerIcon.Hand)
                ) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Member actions",
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier.size(18.dp)
                    )
                }
                DropdownMenu(
                    expanded = showRoleMenu,
                    onDismissRequest = { showRoleMenu = false },
                    containerColor = NostrordColors.Surface
                ) {
                    if (member.isAdmin) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Remove Admin Role",
                                    color = NostrordColors.TextPrimary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = NostrordColors.Warning,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showRoleMenu = false
                                onDemote()
                            }
                        )
                    } else {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Promote to Admin",
                                    color = NostrordColors.TextPrimary,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Shield,
                                    contentDescription = null,
                                    tint = NostrordColors.Primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            },
                            onClick = {
                                showRoleMenu = false
                                onPromote()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Remove from Group",
                                color = NostrordColors.Error,
                                style = MaterialTheme.typography.bodySmall
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = NostrordColors.Error,
                                modifier = Modifier.size(18.dp)
                            )
                        },
                        onClick = {
                            showRoleMenu = false
                            onRemove()
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminMemberAvatar(member: MemberInfo, size: androidx.compose.ui.unit.Dp) {
    val context = LocalPlatformContext.current
    Box(
        modifier = Modifier.size(size).clip(CircleShape),
        contentAlignment = Alignment.Center
    ) {
        var imageState by remember { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
        val showPlaceholder = member.picture.isNullOrBlank() ||
            imageState is AsyncImagePainter.State.Loading ||
            imageState is AsyncImagePainter.State.Error

        if (showPlaceholder) {
            Jdenticon(value = member.pubkey, size = size)
        }
        if (!member.picture.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(member.picture!!)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = member.displayName,
                modifier = Modifier.size(size).clip(CircleShape),
                contentScale = ContentScale.Crop,
                onState = { imageState = it }
            )
        }
    }
}
