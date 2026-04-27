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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.DeclaredChild
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result

/**
 * Admin-only modal for managing this group's children list.
 * Publishes a single `kind:9002` on save with the updated `child` tags
 * and `open-children`/`closed-children` flag.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageChildrenModal(
    groupId: String,
    currentMetadata: GroupMetadata?,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val scope = rememberCoroutineScope()

    var acceptedChildren by remember(currentMetadata) {
        mutableStateOf(currentMetadata?.children.orEmpty())
    }
    var closedChildren by remember(currentMetadata) {
        mutableStateOf(currentMetadata?.closedChildren == true)
    }
    var newChildInput by remember(currentMetadata) { mutableStateOf("") }
    val originalChildren = currentMetadata?.children.orEmpty()
    val originalClosedChildren = currentMetadata?.closedChildren == true

    val allGroups by AppModule.nostrRepository.groups.collectAsState()
    val groupAdmins by AppModule.nostrRepository.groupAdmins.collectAsState()
    val claimsSplit = remember(allGroups, groupId, acceptedChildren, groupAdmins) {
        val approved = acceptedChildren.map { it.id }.toSet()
        val parentAdmins = groupAdmins[groupId].orEmpty().toSet()
        val claims = allGroups.filter { it.parent == groupId && it.id !in approved && it.id != groupId }
        val attested = claims.filter { it.parentAttestation != null && it.parentAttestation in parentAdmins }
        val pending = claims.filter { it.parentAttestation == null || it.parentAttestation !in parentAdmins }
        attested to pending
    }
    val attestedClaims = claimsSplit.first
    val pendingClaims = claimsSplit.second

    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

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
                ) { if (!isSaving) onDismiss() }
                .safeDrawingPadding(),
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
                                text = "Manage Children",
                                style = NostrordTypography.ServerHeader,
                                color = NostrordColors.TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text(
                                text = "Children fall into three groups: bilaterally approved, confirmed by admin attestation, and pending claims. Only bilateral approval removes the ⚠ warning for other clients.",
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

                    EditAccessToggleRow(
                        icon = Icons.Default.Block,
                        label = "Closed children",
                        description = "Only children listed below are accepted; all other parent claims are rejected.",
                        checked = closedChildren,
                        onCheckedChange = { closedChildren = it }
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))
                    EditFieldLabel(
                        if (acceptedChildren.isEmpty()) "Approved children — bilateral (none)"
                        else "Approved children — bilateral (${acceptedChildren.size})"
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

                    if (attestedClaims.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        EditFieldLabel("Confirmed via admin attestation (${attestedClaims.size})")
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = "Signed by an admin of this group — already valid. Pin to the bilateral list to make the relationship explicit.",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextMuted
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        attestedClaims.forEach { claim ->
                            ChildClaimRow(
                                groupId = claim.id,
                                groupName = claim.name,
                                isApproved = false,
                                actionLabel = "Pin",
                                onActionClick = {
                                    acceptedChildren = acceptedChildren + DeclaredChild(id = claim.id)
                                }
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                        }
                    }

                    if (pendingClaims.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        EditFieldLabel("Pending claims (${pendingClaims.size})")
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = "Unilateral claims — not signed by any current admin. Approve to list bilaterally.",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextMuted
                        )
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
                    EditFieldLabel("Add child by ID")
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
                        val changed = acceptedChildren != originalChildren ||
                            closedChildren != originalClosedChildren
                        Button(
                            onClick = {
                                isSaving = true
                                scope.launch {
                                    val result = AppModule.nostrRepository.updateChildren(
                                        groupId = groupId,
                                        children = acceptedChildren,
                                        closedChildren = closedChildren
                                    )
                                    isSaving = false
                                    when (result) {
                                        is Result.Success -> onSaved()
                                        is Result.Error -> errorMessage = result.error.cause?.message
                                            ?: result.error.message
                                            ?: "Failed to update children."
                                    }
                                }
                            },
                            enabled = !isSaving && changed,
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
