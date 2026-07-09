package org.nostr.nostrord.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Settings "Muted users" panel: the account's NIP-51 kind:10000 mute list with an
 * Unmute action per user. Web parity: MutedUsersPanel in web/screens/SettingsScreen.kt.
 */
@Composable
fun MutedUsersPanelContent(vm: MutedUsersViewModel) {
    val muted by vm.muted.collectAsState()
    val userMetadata by vm.userMetadata.collectAsState()

    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "Muted users don't appear in chats, direct messages, or notifications. " +
                "Mutes sync to your other Nostr clients.",
            color = NostrordColors.TextSecondary,
            fontSize = 13.sp,
        )
        Spacer(Modifier.height(Spacing.lg))

        if (muted.isEmpty()) {
            Text(
                text = "You haven't muted anyone. Mute someone from their profile to hide their messages.",
                color = NostrordColors.TextMuted,
                fontSize = 13.sp,
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                muted.forEach { pubkey ->
                    val meta = userMetadata[pubkey]
                    val npub = remember(pubkey) { Nip19.encodeNpub(pubkey) }
                    val displayName =
                        meta?.displayName?.takeIf { it.isNotBlank() }
                            ?: meta?.name?.takeIf { it.isNotBlank() }
                            ?: (npub.take(12) + "…")
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        ProfileAvatar(
                            imageUrl = meta?.picture,
                            displayName = displayName,
                            pubkey = pubkey,
                            size = 36.dp,
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayName,
                                color = NostrordColors.TextPrimary,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = npub.take(20) + "…",
                                color = NostrordColors.TextMuted,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        AppButton(
                            text = "Unmute",
                            onClick = { vm.unmute(pubkey) },
                            variant = AppButtonVariant.Secondary,
                        )
                    }
                }
            }
        }
    }
}
