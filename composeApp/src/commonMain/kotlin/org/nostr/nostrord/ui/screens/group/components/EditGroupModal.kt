package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.DeclaredChild
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.managers.GroupManager
import org.nostr.nostrord.ui.components.upload.UploadImageField
import androidx.compose.runtime.LaunchedEffect
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.rememberClipboardWriter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGroupModal(
    groupId: String,
    currentMetadata: GroupMetadata?,
    onDismiss: () -> Unit,
    onGroupUpdated: () -> Unit,
    showSubgroupControls: Boolean = true
) {
    val scope = rememberCoroutineScope()
    val copyToClipboard = rememberClipboardWriter()

    var name by remember(currentMetadata) { mutableStateOf(currentMetadata?.name ?: "") }
    var about by remember(currentMetadata) { mutableStateOf(currentMetadata?.about ?: "") }
    var picture by remember(currentMetadata) { mutableStateOf(currentMetadata?.picture ?: "") }
    var isPrivate by remember(currentMetadata) { mutableStateOf(currentMetadata?.isPublic == false) }
    var isClosed by remember(currentMetadata) { mutableStateOf(currentMetadata?.isOpen == false) }
    var parentIdInput by remember(currentMetadata) { mutableStateOf(currentMetadata?.parent ?: "") }
    val originalParent = currentMetadata?.parent

    // Parent-side consent state (bilateral `child` declarations + `closed-children` flag).
    // Edits are batched and published as one kind:9002 on save, so the user can approve
    // pending claims / remove existing children / toggle the flag without spraying events.
    var acceptedChildren by remember(currentMetadata) {
        mutableStateOf(currentMetadata?.children.orEmpty())
    }
    var closedChildren by remember(currentMetadata) {
        mutableStateOf(currentMetadata?.closedChildren == true)
    }
    var newChildInput by remember(currentMetadata) { mutableStateOf("") }
    val originalChildren = currentMetadata?.children.orEmpty()
    val originalClosedChildren = currentMetadata?.closedChildren == true

    // Groups currently declaring this group as parent but not in `acceptedChildren` —
    // i.e. claims awaiting approval (covers both Unverified and, if closedChildren was on,
    // previously Invalid ones the admin might now want to accept).
    val allGroups by AppModule.nostrRepository.groups.collectAsState()
    val pendingClaims = remember(allGroups, groupId, acceptedChildren) {
        val approved = acceptedChildren.map { it.id }.toSet()
        allGroups.filter { it.parent == groupId && it.id !in approved && it.id != groupId }
    }

    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Refresh metadata when the modal opens so fields show current relay state
    LaunchedEffect(groupId) {
        AppModule.nostrRepository.refreshGroupMetadata(groupId)
    }

    Dialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isSaving,
            dismissOnClickOutside = !isSaving,
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
                ) { if (!isSaving) onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
            ) {
                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.xxl)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Edit Group",
                                style = NostrordTypography.ServerHeader,
                                color = NostrordColors.TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text(
                                text = "Update your group's name, description, and access settings.",
                                style = NostrordTypography.Caption,
                                color = NostrordColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        IconButton(
                            onClick = { if (!isSaving) onDismiss() },
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

                    Spacer(modifier = Modifier.height(Spacing.xxl))

                    EditFieldLabel("Group ID")
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = groupId,
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextPrimary,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        IconButton(
                            onClick = { copyToClipboard(groupId) },
                            modifier = Modifier
                                .size(32.dp)
                                .pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy group ID",
                                tint = NostrordColors.TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Group Name
                    EditFieldLabel("Group Name")
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it; errorMessage = null },
                        placeholder = {
                            Text(
                                "#example",
                                color = NostrordColors.TextMuted,
                                style = NostrordTypography.MessageBody
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = editFieldColors(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Description
                    EditFieldLabel("Description")
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    OutlinedTextField(
                        value = about,
                        onValueChange = { about = it },
                        placeholder = {
                            Text(
                                "What is this group about?",
                                color = NostrordColors.TextMuted,
                                style = NostrordTypography.MessageBody
                            )
                        },
                        minLines = 3,
                        maxLines = 5,
                        modifier = Modifier.fillMaxWidth(),
                        colors = editFieldColors(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    UploadImageField(
                        label = "Group Image URL",
                        value = picture,
                        onValueChange = { picture = it },
                        placeholder = "https://example.com/image.jpg",
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(Spacing.xxl))

                    // Access settings
                    Text(
                        text = "ACCESS SETTINGS",
                        style = NostrordTypography.SectionHeader,
                        color = NostrordColors.TextMuted
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))

                    EditAccessToggleRow(
                        icon = Icons.Default.Lock,
                        label = "Private",
                        description = "Only members can read group messages",
                        checked = isPrivate,
                        onCheckedChange = { isPrivate = it }
                    )

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    EditAccessToggleRow(
                        icon = Icons.Default.Block,
                        label = "Closed",
                        description = "Join requests are ignored (invite-only)",
                        checked = isClosed,
                        onCheckedChange = { isClosed = it }
                    )

                    if (showSubgroupControls) {
                    Spacer(modifier = Modifier.height(Spacing.xxl))

                    Text(
                        text = "HIERARCHY",
                        style = NostrordTypography.SectionHeader,
                        color = NostrordColors.TextMuted
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))

                    EditFieldLabel("Parent group ID (leave empty to detach)")
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    OutlinedTextField(
                        value = parentIdInput,
                        onValueChange = { parentIdInput = it.trim() },
                        placeholder = {
                            Text(
                                "parent-group-id",
                                color = NostrordColors.TextMuted,
                                style = NostrordTypography.MessageBody
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = editFieldColors(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(Spacing.xxl))

                    Text(
                        text = "CHILD GROUPS",
                        style = NostrordTypography.SectionHeader,
                        color = NostrordColors.TextMuted
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Text(
                        text = "Approve subgroups that declare this group as parent. Bilateral approval removes the ⚠ warning shown to other users.",
                        style = NostrordTypography.Caption,
                        color = NostrordColors.TextSecondary
                    )

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    EditAccessToggleRow(
                        icon = Icons.Default.Block,
                        label = "Closed children",
                        description = "Only children listed below are accepted; all other parent claims are rejected.",
                        checked = closedChildren,
                        onCheckedChange = { closedChildren = it }
                    )

                    if (pendingClaims.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        EditFieldLabel("Pending claims (${pendingClaims.size})")
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        pendingClaims.forEach { claim ->
                            ChildClaimRow(
                                groupId = claim.id,
                                groupName = claim.name,
                                isApproved = false,
                                onActionClick = {
                                    acceptedChildren = acceptedChildren + DeclaredChild(id = claim.id)
                                }
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))
                    EditFieldLabel(
                        if (acceptedChildren.isEmpty()) "Approved children (none)"
                        else "Approved children (${acceptedChildren.size})"
                    )
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    acceptedChildren.forEach { child ->
                        val claimedName = allGroups.firstOrNull { it.id == child.id }?.name
                        ChildClaimRow(
                            groupId = child.id,
                            groupName = claimedName,
                            isApproved = true,
                            onActionClick = {
                                acceptedChildren = acceptedChildren.filterNot { it.id == child.id }
                            }
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                    }

                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newChildInput,
                            onValueChange = { newChildInput = it.trim() },
                            placeholder = {
                                Text(
                                    "child-group-id",
                                    color = NostrordColors.TextMuted,
                                    style = NostrordTypography.MessageBody
                                )
                            },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = editFieldColors(),
                            shape = RoundedCornerShape(8.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        TextButton(
                            onClick = {
                                val id = newChildInput.trim()
                                if (id.isNotBlank() && acceptedChildren.none { it.id == id } && id != groupId) {
                                    acceptedChildren = acceptedChildren + DeclaredChild(id = id)
                                    newChildInput = ""
                                }
                            },
                            enabled = newChildInput.isNotBlank() &&
                                acceptedChildren.none { it.id == newChildInput } &&
                                newChildInput != groupId
                        ) {
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = null,
                                tint = NostrordColors.Primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("Add", color = NostrordColors.Primary)
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    }

                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Text(
                            text = errorMessage!!,
                            style = NostrordTypography.Caption,
                            color = NostrordColors.Error
                        )
                    }

                    Spacer(modifier = Modifier.height(Spacing.xxl))

                    // Footer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !isSaving
                        ) {
                            Text("Cancel", color = NostrordColors.TextSecondary)
                        }
                        Button(
                            onClick = {
                                if (name.isBlank()) {
                                    errorMessage = "Group name is required."
                                    return@Button
                                }
                                isSaving = true
                                scope.launch {
                                    val result = AppModule.nostrRepository.editGroup(
                                        groupId = groupId,
                                        name = name.trim(),
                                        about = about.trim().ifBlank { null },
                                        isPrivate = isPrivate,
                                        isClosed = isClosed,
                                        picture = picture.trim().ifBlank { null }
                                    )
                                    isSaving = false
                                    when (result) {
                                        is Result.Success -> {
                                            val newParent = parentIdInput.trim().ifBlank { null }
                                            val parentOp: GroupManager.ParentOp? = when {
                                                newParent == originalParent -> null
                                                newParent == null -> GroupManager.ParentOp.Detach
                                                else -> GroupManager.ParentOp.SetTo(newParent)
                                            }
                                            if (parentOp != null) {
                                                val topoResult = AppModule.nostrRepository.updateGroupTopology(
                                                    groupId = groupId,
                                                    parent = parentOp
                                                )
                                                if (topoResult is Result.Error) {
                                                    isSaving = false
                                                    errorMessage = topoResult.error.cause?.message
                                                        ?: topoResult.error.message
                                                        ?: "Failed to update hierarchy."
                                                    return@launch
                                                }
                                            }
                                            val childrenChanged = acceptedChildren != originalChildren ||
                                                closedChildren != originalClosedChildren
                                            if (childrenChanged) {
                                                val childResult = AppModule.nostrRepository.updateChildren(
                                                    groupId = groupId,
                                                    children = acceptedChildren,
                                                    closedChildren = closedChildren
                                                )
                                                if (childResult is Result.Error) {
                                                    isSaving = false
                                                    errorMessage = childResult.error.cause?.message
                                                        ?: childResult.error.message
                                                        ?: "Failed to update child list."
                                                    return@launch
                                                }
                                            }
                                            AppModule.nostrRepository.refreshGroupMetadata(groupId)
                                            onGroupUpdated()
                                        }
                                        is Result.Error -> errorMessage = result.error.cause?.message
                                            ?: result.error.message
                                            ?: "Failed to update group."
                                    }
                                }
                            },
                            enabled = !isSaving && name.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NostrordColors.Primary,
                                contentColor = Color.White,
                                disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            if (isSaving) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(Spacing.sm))
                            }
                            Text("Save Changes", style = NostrordTypography.Button)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditFieldLabel(text: String) {
    Text(
        text = text,
        style = NostrordTypography.Caption,
        color = NostrordColors.TextSecondary,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun EditAccessToggleRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column {
                Text(
                    text = label,
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = description,
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextMuted
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NostrordColors.Primary,
                uncheckedThumbColor = NostrordColors.TextMuted,
                uncheckedTrackColor = NostrordColors.InputBackground
            ),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        )
    }
}

@Composable
private fun ChildClaimRow(
    groupId: String,
    groupName: String?,
    isApproved: Boolean,
    onActionClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = groupName?.takeIf { it.isNotBlank() } ?: groupId,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextPrimary,
                fontWeight = FontWeight.Medium
            )
            if (!groupName.isNullOrBlank()) {
                Text(
                    text = groupId,
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextMuted
                )
            }
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        TextButton(
            onClick = onActionClick,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(
                imageVector = if (isApproved) Icons.Default.Close else Icons.Default.Check,
                contentDescription = null,
                tint = if (isApproved) NostrordColors.Error else NostrordColors.Primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(
                text = if (isApproved) "Remove" else "Approve",
                color = if (isApproved) NostrordColors.Error else NostrordColors.Primary,
                style = NostrordTypography.Caption
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun editFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = NostrordColors.TextPrimary,
    unfocusedTextColor = NostrordColors.TextPrimary,
    focusedBorderColor = NostrordColors.Primary,
    unfocusedBorderColor = NostrordColors.Divider,
    cursorColor = NostrordColors.Primary,
    focusedContainerColor = NostrordColors.InputBackground,
    unfocusedContainerColor = NostrordColors.InputBackground,
    focusedPlaceholderColor = NostrordColors.TextMuted,
    unfocusedPlaceholderColor = NostrordColors.TextMuted,
    focusedTrailingIconColor = NostrordColors.TextSecondary,
    unfocusedTrailingIconColor = NostrordColors.TextMuted
)
