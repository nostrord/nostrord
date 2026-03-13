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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result

private val KNOWN_RELAYS = listOf(
    "wss://groups.0xchat.com",
    "wss://groups.fiatjaf.com",
    "wss://relay.groups.nip29.com",
    "wss://groups.hzrd149.com",
    "wss://pyramid.fiatjaf.com"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateGroupModal(
    currentRelayUrl: String,
    onDismiss: () -> Unit,
    onGroupCreated: (groupId: String, groupName: String) -> Unit
) {
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var isClosed by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val relayOptions = remember(currentRelayUrl) {
        if (currentRelayUrl in KNOWN_RELAYS) KNOWN_RELAYS
        else listOf(currentRelayUrl) + KNOWN_RELAYS
    }
    var selectedRelay by remember(currentRelayUrl) { mutableStateOf(currentRelayUrl) }
    var relayDropdownExpanded by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = { if (!isCreating) onDismiss() },
        properties = DialogProperties(
            dismissOnBackPress = !isCreating,
            dismissOnClickOutside = !isCreating,
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
                ) { if (!isCreating) onDismiss() },
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
                                text = "Create a Group",
                                style = NostrordTypography.ServerHeader,
                                color = NostrordColors.TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                            Text(
                                text = "Give your new group a name and description. You can always change these later.",
                                style = NostrordTypography.Caption,
                                color = NostrordColors.TextSecondary
                            )
                        }
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        IconButton(
                            onClick = { if (!isCreating) onDismiss() },
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

                    // Group Name
                    FieldLabel("Group Name")
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
                        colors = fieldColors(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Relay selector
                    FieldLabel("Relay")
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    ExposedDropdownMenuBox(
                        expanded = relayDropdownExpanded,
                        onExpandedChange = { relayDropdownExpanded = it }
                    ) {
                        OutlinedTextField(
                            value = selectedRelay.removePrefix("wss://"),
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = relayDropdownExpanded)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            colors = fieldColors(),
                            shape = RoundedCornerShape(8.dp)
                        )
                        ExposedDropdownMenu(
                            expanded = relayDropdownExpanded,
                            onDismissRequest = { relayDropdownExpanded = false },
                            containerColor = NostrordColors.SurfaceVariant
                        ) {
                            relayOptions.forEach { relay ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            relay.removePrefix("wss://"),
                                            color = if (relay == selectedRelay)
                                                NostrordColors.Primary
                                            else
                                                NostrordColors.TextPrimary,
                                            style = NostrordTypography.MessageBody
                                        )
                                    },
                                    onClick = {
                                        selectedRelay = relay
                                        relayDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // Description
                    FieldLabel("Description")
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
                        colors = fieldColors(),
                        shape = RoundedCornerShape(8.dp)
                    )

                    Spacer(modifier = Modifier.height(Spacing.xxl))

                    // Access settings
                    Text(
                        text = "ACCESS SETTINGS",
                        style = NostrordTypography.SectionHeader,
                        color = NostrordColors.TextMuted
                    )
                    Spacer(modifier = Modifier.height(Spacing.sm))

                    AccessToggleRow(
                        icon = Icons.Default.Lock,
                        label = "Private",
                        description = "Only members can read group messages",
                        checked = isPrivate,
                        onCheckedChange = { isPrivate = it }
                    )

                    Spacer(modifier = Modifier.height(Spacing.xs))

                    AccessToggleRow(
                        icon = Icons.Default.Block,
                        label = "Closed",
                        description = "Join requests are ignored (invite-only)",
                        checked = isClosed,
                        onCheckedChange = { isClosed = it }
                    )

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
                            enabled = !isCreating
                        ) {
                            Text("Cancel", color = NostrordColors.TextSecondary)
                        }
                        Button(
                            onClick = {
                                if (name.isBlank()) {
                                    errorMessage = "Group name is required."
                                    return@Button
                                }
                                isCreating = true
                                scope.launch {
                                    val result = NostrRepository.createGroup(
                                        name = name.trim(),
                                        about = about.trim().ifBlank { null },
                                        relayUrl = selectedRelay,
                                        isPrivate = isPrivate,
                                        isClosed = isClosed
                                    )
                                    isCreating = false
                                    when (result) {
                                        is Result.Success -> onGroupCreated(result.data, name.trim())
                                        is Result.Error -> errorMessage = result.error.cause?.message
                                            ?: result.error.message
                                    }
                                }
                            },
                            enabled = !isCreating && name.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = NostrordColors.Primary,
                                contentColor = Color.White,
                                disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.5f),
                                disabledContentColor = Color.White.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                        ) {
                            if (isCreating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(Spacing.sm))
                            }
                            Text("Create Group", style = NostrordTypography.Button)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text,
        style = NostrordTypography.Caption,
        color = NostrordColors.TextSecondary,
        fontWeight = FontWeight.Medium
    )
}

@Composable
private fun AccessToggleRow(
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
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
