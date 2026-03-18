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
import androidx.compose.material.icons.filled.Info
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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
    val uriHandler = LocalUriHandler.current

    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var isPrivate by remember { mutableStateOf(false) }
    var isClosed by remember { mutableStateOf(false) }
    var isCreating by remember { mutableStateOf(false) }
    var creatingJob by remember { mutableStateOf<Job?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val relayOptions = remember(currentRelayUrl) {
        if (currentRelayUrl in KNOWN_RELAYS) KNOWN_RELAYS
        else listOf(currentRelayUrl) + KNOWN_RELAYS
    }
    var selectedRelay by remember(currentRelayUrl) { mutableStateOf(currentRelayUrl) }
    var relayDropdownExpanded by remember { mutableStateOf(false) }

    val relayWebUrl = selectedRelay.replace("wss://", "https://").replace("ws://", "http://")

    fun cancelCreation() {
        creatingJob?.cancel()
        creatingJob = null
        isCreating = false
    }

    Dialog(
        onDismissRequest = {
            if (isCreating) cancelCreation()
            onDismiss()
        },
        properties = DialogProperties(
            dismissOnBackPress = true,
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
                            onClick = {
                                if (isCreating) cancelCreation()
                                onDismiss()
                            },
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
                        onExpandedChange = { if (!isCreating) relayDropdownExpanded = it }
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
                                        errorMessage = null
                                        relayDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    // Always show a direct link to create the group on the relay's website.
                    // Most NIP-29 relays require web-based creation; this sets the right expectation upfront.
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Text(
                            text = "Some relays require creating groups via their website.",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextMuted
                        )
                        Text(
                            text = "Open →",
                            style = NostrordTypography.Caption,
                            color = NostrordColors.Primary,
                            textDecoration = TextDecoration.Underline,
                            modifier = Modifier
                                .pointerHoverIcon(PointerIcon.Hand)
                                .clickable { uriHandler.openUri(relayWebUrl) }
                        )
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

                    // Error message
                    if (errorMessage != null) {
                        Spacer(modifier = Modifier.height(Spacing.md))
                        Row(
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Text(
                                text = errorMessage!!,
                                style = NostrordTypography.Caption,
                                color = NostrordColors.Error,
                                modifier = Modifier.weight(1f)
                            )
                            // If the error is a restriction, offer a link
                            val showLink = errorMessage!!.contains("not allowed", ignoreCase = true) ||
                                errorMessage!!.contains("restricted", ignoreCase = true) ||
                                errorMessage!!.contains("authorization", ignoreCase = true) ||
                                errorMessage!!.contains("auth-required", ignoreCase = true) ||
                                errorMessage!!.contains("blocked", ignoreCase = true)
                            if (showLink) {
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text(
                                    text = "Visit relay →",
                                    style = NostrordTypography.Caption,
                                    color = NostrordColors.Error,
                                    textDecoration = TextDecoration.Underline,
                                    modifier = Modifier
                                        .pointerHoverIcon(PointerIcon.Hand)
                                        .clickable { uriHandler.openUri(relayWebUrl) }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.xxl))

                    // Footer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm, Alignment.End),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                cancelCreation()
                                onDismiss()
                            }
                        ) {
                            Text(
                                "Cancel",
                                color = if (isCreating) NostrordColors.Error else NostrordColors.TextSecondary
                            )
                        }
                        Button(
                            onClick = {
                                if (name.isBlank()) {
                                    errorMessage = "Group name is required."
                                    return@Button
                                }
                                errorMessage = null
                                isCreating = true
                                creatingJob = scope.launch {
                                    try {
                                        val result = NostrRepository.createGroup(
                                            name = name.trim(),
                                            about = about.trim().ifBlank { null },
                                            relayUrl = selectedRelay,
                                            isPrivate = isPrivate,
                                            isClosed = isClosed
                                        )
                                        isCreating = false
                                        creatingJob = null
                                        when (result) {
                                            is Result.Success -> onGroupCreated(result.data, name.trim())
                                            is Result.Error -> {
                                                val raw = result.error.cause?.message ?: result.error.message
                                                errorMessage = friendlyError(raw)
                                            }
                                        }
                                    } catch (_: CancellationException) {
                                        // user cancelled — no error shown
                                        isCreating = false
                                        creatingJob = null
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

private fun friendlyError(raw: String?): String = when {
    raw == null -> "Something went wrong. Try again."
    // "blocked: to create groups open https://... in your web browser"
    raw.contains("blocked:", ignoreCase = true) ->
        "Group creation on this relay must be done via the relay's website."
    raw.contains("auth-required", ignoreCase = true) ||
    raw.contains("not allowed", ignoreCase = true) ||
    raw.contains("restricted", ignoreCase = true) ->
        "This relay requires authorization to create groups."
    raw.contains("did not respond", ignoreCase = true) ||
    raw.contains("timeout", ignoreCase = true) ->
        "Relay did not respond. Try again."
    raw.contains("Not connected", ignoreCase = true) ->
        "Not connected to relay. Try again."
    else -> raw
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
