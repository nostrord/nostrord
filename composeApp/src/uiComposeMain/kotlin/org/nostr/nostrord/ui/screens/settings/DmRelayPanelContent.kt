package org.nostr.nostrord.ui.screens.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Error
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
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.isValidRelayUrl
import org.nostr.nostrord.utils.toRelayUrl

/**
 * Settings panel for the NIP-17 DM relay list (kind:10050). Mirrors the web DmRelaysPanel and
 * shares [DmRelaySettingsViewModel] so add/remove/publish behavior lives in commonMain.
 */
@Composable
fun DmRelayPanelContent() {
    val vm = viewModel { DmRelaySettingsViewModel(AppModule.nostrRepository) }
    val published by vm.relays.collectAsState()
    val saving by vm.saving.collectAsState()
    val error by vm.error.collectAsState()

    // Local editable copy — reseeds when the published list first populates (or changes).
    var relays by remember(published) { mutableStateOf(published) }
    var newUrl by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.lg)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Text(
                text = "NIP-17 DM relays (kind 10050) tell other clients where to deliver your private " +
                    "direct messages. Pick a few reliable relays. They can be the same as your NIP-65 relays " +
                    "or dedicated ones. Until you publish a list, sensible defaults are used.",
                color = NostrordColors.TextSecondary,
                fontSize = 13.sp,
                modifier = Modifier.padding(Spacing.lg),
            )
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.cardShape,
            colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(Spacing.xl),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                Text(
                    text = "YOUR DM RELAYS",
                    style = NostrordTypography.SectionHeader,
                    color = NostrordColors.TextMuted,
                )
                Spacer(Modifier.height(Spacing.sm))

                if (relays.isEmpty()) {
                    Text(
                        text = "No DM relays. Others won't be able to reach you. Add at least one.",
                        color = NostrordColors.TextMuted,
                        fontSize = 13.sp,
                    )
                } else {
                    relays.forEachIndexed { index, url ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            Text(
                                text = url.removePrefix("wss://").removePrefix("ws://"),
                                color = NostrordColors.TextPrimary,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(
                                onClick = { relays = relays.toMutableList().also { it.removeAt(index) } },
                                modifier = Modifier.size(28.dp).pointerHoverIcon(PointerIcon.Hand),
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Remove relay",
                                    tint = NostrordColors.TextMuted,
                                    modifier = Modifier.size(14.dp),
                                )
                            }
                        }
                        if (index < relays.lastIndex) HorizontalDivider(color = NostrordColors.Divider)
                    }
                }

                Spacer(Modifier.height(Spacing.sm))
                HorizontalDivider(color = NostrordColors.Divider)
                Spacer(Modifier.height(Spacing.sm))

                Text(
                    text = "ADD RELAY",
                    style = NostrordTypography.SectionHeader,
                    color = NostrordColors.TextMuted,
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
                        focusedTextColor = NostrordColors.TextContent,
                        unfocusedTextColor = NostrordColors.TextContent,
                    ),
                    shape = NostrordShapes.shapeSmall,
                )

                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Spacer(Modifier.weight(1f))
                    val normalizedNewUrl = newUrl.trim().toRelayUrl()
                    val canAdd = isValidRelayUrl(normalizedNewUrl) && relays.none { it == normalizedNewUrl }
                    val addTint = if (canAdd) NostrordColors.Primary else NostrordColors.TextMuted
                    TextButton(
                        onClick = {
                            relays = relays + normalizedNewUrl
                            newUrl = ""
                        },
                        enabled = canAdd,
                        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, tint = addTint, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add", color = addTint, style = NostrordTypography.Button)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = { vm.publish(relays) },
                enabled = !saving,
                modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = NostrordColors.Primary,
                        strokeWidth = 2.dp,
                    )
                } else {
                    Text("Save & Publish", color = NostrordColors.Primary, style = NostrordTypography.Button)
                }
            }
        }

        error?.let { message ->
            Card(
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Error),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(message, color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
