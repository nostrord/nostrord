package org.nostr.nostrord.ui.screens.dm

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.forms.AppTextField
import org.nostr.nostrord.ui.navigation.UserRoute
import org.nostr.nostrord.ui.screens.profile.ProfilePageViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Direct-message conversation page (prototype DirectMessage, NIP-17 style). The
 * message backend does not exist yet: the conversation intro and the composer are
 * in place, with sending disabled until NIP-17 lands. Mirrors the web
 * web/screens/DmPage.
 */
@Composable
fun DmPageScreen(
    pubkey: String?,
    onOpenProfile: (UserRoute) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize().background(NostrordColors.Background)) {
        if (pubkey == null) {
            Row(
                modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Spacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Direct messages",
                    color = NostrordColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            HorizontalDivider(color = NostrordColors.Divider)
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Box(
                    modifier =
                    Modifier
                        .size(64.dp)
                        .clip(NostrordShapes.shapeXLarge)
                        .background(NostrordColors.BackgroundFloating),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("✉️", fontSize = 30.sp)
                }
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    "Your direct messages",
                    color = NostrordColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    "Pick a conversation on the side or start a new one with someone you follow.",
                    color = NostrordColors.TextMuted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.widthIn(max = 320.dp),
                )
            }
            return@Column
        }

        val vm = viewModel(key = "dm-$pubkey") { ProfilePageViewModel(AppModule.nostrRepository, pubkey) }
        val metadata by vm.metadata.collectAsState()
        val dmVm = viewModel { DmViewModel(AppModule.nostrRepository) }
        val messagesByPeer by dmVm.messagesByPeer.collectAsState()
        val messages = messagesByPeer[pubkey].orEmpty()
        // Mark the conversation read while it is open (and as new messages stream in).
        LaunchedEffect(pubkey, messages.size) {
            if (messages.isNotEmpty()) dmVm.markRead(pubkey)
        }
        var text by remember { mutableStateOf("") }
        val send = {
            if (text.isNotBlank()) {
                dmVm.send(pubkey, text)
                text = ""
            }
        }
        val name =
            metadata?.displayName?.takeIf { it.isNotBlank() }
                ?: metadata?.name?.takeIf { it.isNotBlank() }
                ?: vm.npub.take(12) + "..."

        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Row(
                modifier =
                Modifier
                    .clip(NostrordShapes.shapeSmall)
                    .clickable { onOpenProfile(UserRoute(pubkey)) }
                    .padding(Spacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                OptimizedSmallAvatar(
                    imageUrl = metadata?.picture,
                    identifier = pubkey,
                    displayName = name,
                    size = 24.dp,
                    shape = CircleShape,
                )
                Text(
                    name,
                    color = NostrordColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Surface(shape = NostrordShapes.shapeSmall, color = NostrordColors.BackgroundFloating) {
                Text(
                    "DM · encrypted",
                    color = NostrordColors.TextMuted,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        HorizontalDivider(color = NostrordColors.Divider)

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(Spacing.lg),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OptimizedSmallAvatar(
                imageUrl = metadata?.picture,
                identifier = pubkey,
                displayName = name,
                size = 64.dp,
                shape = CircleShape,
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                name,
                color = NostrordColors.TextPrimary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Beginning of your direct conversation with $name. Direct messages are encrypted (NIP-17).",
                color = NostrordColors.TextMuted,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
            messages.forEach { m ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
                    horizontalArrangement = if (m.mine) Arrangement.End else Arrangement.Start,
                ) {
                    Surface(
                        shape = NostrordShapes.shapeMedium,
                        color = if (m.mine) NostrordColors.Primary else NostrordColors.BackgroundFloating,
                        modifier = Modifier.widthIn(max = 320.dp),
                    ) {
                        Text(
                            m.content,
                            color = if (m.mine) Color.White else NostrordColors.TextPrimary,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm),
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.lg).padding(bottom = Spacing.xl, top = Spacing.xs),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            IconButton(onClick = {}, enabled = false) {
                Icon(
                    imageVector = Icons.Default.AttachFile,
                    contentDescription = "Attach",
                    tint = NostrordColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            AppTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Message $name",
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {}, enabled = false) {
                Icon(
                    imageVector = Icons.Default.EmojiEmotions,
                    contentDescription = "Emoji",
                    tint = NostrordColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = send, enabled = text.isNotBlank()) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = if (text.isNotBlank()) NostrordColors.Primary else NostrordColors.TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
