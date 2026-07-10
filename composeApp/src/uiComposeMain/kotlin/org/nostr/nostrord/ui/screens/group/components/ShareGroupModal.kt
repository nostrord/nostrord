package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.IdentifierRow
import org.nostr.nostrord.ui.components.ModalTitleBar
import org.nostr.nostrord.ui.groupIdentifiers
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

@Composable
fun ShareGroupModal(
    relayUrl: String,
    groupId: String,
    onDismiss: () -> Unit,
) {
    val relayMetadata by AppModule.nostrRepository.relayMetadata.collectAsState()
    val relayPubkey = relayMetadata[relayUrl]?.groupNaddrAuthor ?: relayMetadata[relayUrl.trimEnd('/')]?.groupNaddrAuthor

    Dialog(
        onDismissRequest = onDismiss,
        properties =
        DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier =
                Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { /* consume click */ },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
            ) {
                Column {
                    ModalTitleBar(title = "Share Group", onClose = onDismiss)
                    // A single cycling identifier field (relay'groupId / naddr / nostrord link)
                    // instead of one copy input per format. Shares the .identifier-* object +
                    // GROUP ADDRESS head with the group-info / profile UIs.
                    Column(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg)
                            .padding(top = Spacing.md, bottom = Spacing.lg),
                    ) {
                        Text(
                            "GROUP ADDRESS",
                            style = NostrordTypography.SectionHeader,
                            color = NostrordColors.TextMuted,
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        IdentifierRow(ids = groupIdentifiers(relayUrl, groupId, relayPubkey))
                    }
                }
            }
        }
    }
}
