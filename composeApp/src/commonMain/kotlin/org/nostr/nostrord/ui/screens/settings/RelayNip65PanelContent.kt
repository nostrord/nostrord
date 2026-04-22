package org.nostr.nostrord.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.outbox.Nip65Relay
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.Result
import org.nostr.nostrord.utils.isValidRelayUrl
import org.nostr.nostrord.utils.toRelayUrl

@Composable
fun RelayNip65PanelContent(
    currentRelays: List<Nip65Relay>,
    usingDefaults: Boolean = false,
    onPublish: suspend (List<Nip65Relay>) -> Result<Unit>
) {
    val scope = rememberCoroutineScope()

    // Local editable copy — reinitialise when server list first populates.
    var relays by remember(currentRelays.isNotEmpty()) {
        mutableStateOf(currentRelays)
    }

    var newUrl by remember { mutableStateOf("") }
    var newRead by remember { mutableStateOf(true) }
    var newWrite by remember { mutableStateOf(true) }

    var isSaving by remember { mutableStateOf(false) }
    var showSuccess by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(showSuccess) {
        if (showSuccess) { kotlinx.coroutines.delay(2500); showSuccess = false }
    }
    LaunchedEffect(saveError) {
        if (saveError != null) { kotlinx.coroutines.delay(3500); saveError = null }
    }

    val hasNoRead  = relays.none { it.read }
    val hasNoWrite = relays.none { it.write }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {

        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
        ) {
            Text(
                text = "NIP-65 relay list (kind 10002) is where other clients find your profile " +
                        "and your joined groups list (kind 10009). " +
                        "Write relays are where you publish; read relays are for cross-network discoverability. " +
                        "Group messages are separate — they live on each group's relay.",
                color = NostrordColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(Spacing.lg)
            )
        }

        // "Using defaults" warning banner
        if (usingDefaults) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = NostrordShapes.cardShape,
                colors = CardDefaults.cardColors(
                    containerColor = NostrordColors.WarningOrange.copy(alpha = 0.12f)
                )
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    verticalAlignment = Alignment.Top
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = NostrordColors.WarningOrange,
                        modifier = Modifier.size(18.dp).padding(top = 2.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            text = "Using default relays",
                            color = NostrordColors.WarningOrange,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                        Text(
                            text = "No relay list (kind 10002) was found for this account. " +
                                    "Publish your relay list so others can find your profile and groups.",
                            color = NostrordColors.WarningOrange,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Relay list card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = "YOUR RELAYS",
                    style = NostrordTypography.SectionHeader,
                    color = NostrordColors.TextMuted
                )

                Spacer(Modifier.height(Spacing.sm))

                if (relays.isEmpty()) {
                    Text(
                        text = "No relays configured. Add at least one read relay and one write relay.",
                        color = NostrordColors.TextMuted,
                        fontSize = 13.sp
                    )
                } else {
                    relays.forEachIndexed { index, relay ->
                        RelayRow(
                            relay = relay,
                            onReadToggle = {
                                relays = relays.toMutableList().also {
                                    it[index] = relay.copy(read = !relay.read)
                                }
                            },
                            onWriteToggle = {
                                relays = relays.toMutableList().also {
                                    it[index] = relay.copy(write = !relay.write)
                                }
                            },
                            onDelete = {
                                relays = relays.toMutableList().also { it.removeAt(index) }
                            }
                        )
                        if (index < relays.lastIndex) {
                            HorizontalDivider(color = NostrordColors.Divider)
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.sm))
                HorizontalDivider(color = NostrordColors.Divider)
                Spacer(Modifier.height(Spacing.sm))

                // Add relay form
                Text(
                    text = "ADD RELAY",
                    style = NostrordTypography.SectionHeader,
                    color = NostrordColors.TextMuted
                )
                Spacer(Modifier.height(Spacing.sm))

                OutlinedTextField(
                    value = newUrl,
                    onValueChange = { newUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("relay.example.com", color = NostrordColors.TextMuted) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NostrordColors.Primary,
                        unfocusedBorderColor = NostrordColors.Divider,
                        focusedContainerColor = NostrordColors.InputBackground,
                        unfocusedContainerColor = NostrordColors.InputBackground,
                        cursorColor = NostrordColors.Primary,
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White
                    ),
                    shape = NostrordShapes.shapeSmall
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xl)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Checkbox(
                            checked = newRead,
                            onCheckedChange = { newRead = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = NostrordColors.Primary,
                                uncheckedColor = NostrordColors.TextMuted
                            )
                        )
                        Text("Read", color = NostrordColors.TextSecondary, fontSize = 14.sp)
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Checkbox(
                            checked = newWrite,
                            onCheckedChange = { newWrite = it },
                            colors = CheckboxDefaults.colors(
                                checkedColor = NostrordColors.Primary,
                                uncheckedColor = NostrordColors.TextMuted
                            )
                        )
                        Text("Write", color = NostrordColors.TextSecondary, fontSize = 14.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    val normalizedNewUrl = newUrl.trim().toRelayUrl()
                    val canAdd = isValidRelayUrl(normalizedNewUrl) &&
                        (newRead || newWrite) &&
                        relays.none { it.url == normalizedNewUrl }
                    val addTint = if (canAdd) NostrordColors.Primary else NostrordColors.TextMuted
                    TextButton(
                        onClick = {
                            relays = relays + Nip65Relay(normalizedNewUrl, newRead, newWrite)
                            newUrl = ""
                            newRead = true
                            newWrite = true
                        },
                        enabled = canAdd,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            tint = addTint,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Add", color = addTint, style = NostrordTypography.Button)
                    }
                }
            }
        }

        // Warnings
        if (hasNoRead || hasNoWrite) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.WarningOrange.copy(alpha = 0.15f))
            ) {
                Column(modifier = Modifier.padding(Spacing.lg), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (hasNoRead)  Text("⚠ No read relay — cross-network discoverability will be limited.", color = NostrordColors.WarningOrange, fontSize = 13.sp)
                    if (hasNoWrite) Text("⚠ No write relay — your profile and joined groups list won't be discoverable.", color = NostrordColors.WarningOrange, fontSize = 13.sp)
                }
            }
        }

        // Save button
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(
                onClick = {
                    isSaving = true
                    saveError = null
                    scope.launch {
                        val result = onPublish(relays)
                        isSaving = false
                        when (result) {
                            is Result.Success -> showSuccess = true
                            is Result.Error   -> saveError = result.error.message ?: "Failed to publish"
                        }
                    }
                },
                enabled = !isSaving,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = NostrordColors.Primary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Save & Publish", color = NostrordColors.Primary, style = NostrordTypography.Button)
                }
            }
        }

        // Feedback
        when {
            showSuccess -> StatusCard(
                icon = Icons.Default.Check,
                message = "Relay list published",
                color = NostrordColors.Success
            )
            saveError != null -> StatusCard(
                icon = Icons.Default.Error,
                message = saveError!!,
                color = NostrordColors.Error
            )
        }
    }
}

@Composable
private fun RelayRow(
    relay: Nip65Relay,
    onReadToggle: () -> Unit,
    onWriteToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = relay.url,
            color = NostrordColors.TextPrimary,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        RelayChip(label = "R", active = relay.read, onClick = onReadToggle)
        RelayChip(label = "W", active = relay.write, onClick = onWriteToggle)
        IconButton(
            onClick = onDelete,
            modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand)
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove relay",
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(14.dp)
            )
        }
    }
}

@Composable
private fun RelayChip(label: String, active: Boolean, onClick: () -> Unit) {
    val bg = if (active) NostrordColors.Primary.copy(alpha = 0.2f) else NostrordColors.SurfaceVariant
    val textColor = if (active) NostrordColors.Primary else NostrordColors.TextMuted
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(4.dp),
        color = bg,
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand)
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun StatusCard(icon: androidx.compose.ui.graphics.vector.ImageVector, message: String, color: Color) {
    Card(
        colors = CardDefaults.cardColors(containerColor = color),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(message, color = Color.White, fontWeight = FontWeight.Bold)
        }
    }
}
