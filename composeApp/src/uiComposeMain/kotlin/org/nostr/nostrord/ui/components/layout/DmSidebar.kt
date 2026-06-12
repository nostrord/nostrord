package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.forms.AppTextField
import org.nostr.nostrord.ui.navigation.DmRoute
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Second column on the DM section (prototype DMSidebar, obelisk-style): header with
 * search, Follows / Others tabs and the conversation list. There is no DM backend
 * yet, so the lists are empty; searching a valid npub/hex already opens that
 * conversation. Mirrors the web web/DmSidebar.
 */
@Composable
fun DmSidebar(onOpenConversation: (DmRoute) -> Unit) {
    var tab by remember { mutableStateOf(0) }
    var searchOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val parsed = Nip19.parsePubkeyInput(query) as? Nip19.PubkeyParse.Ok

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(start = Spacing.lg, end = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Direct messages",
                color = NostrordColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                searchOpen = !searchOpen
                query = ""
            }) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                    tint = if (searchOpen) NostrordColors.TextPrimary else NostrordColors.TextMuted,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        HorizontalDivider(color = NostrordColors.Divider)

        Column(modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (searchOpen) {
                Box(modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm)) {
                    AppTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search by name, nip-05, npub or hex",
                    )
                }
                // A pasted npub/hex starts that conversation right away.
                if (parsed != null) {
                    Text(
                        "START A CONVERSATION",
                        color = NostrordColors.TextMuted,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp,
                        modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.xs),
                    )
                    StartConversationRow(pubkeyHex = parsed.hex) {
                        onOpenConversation(DmRoute(parsed.hex))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                listOf("Follows", "Others").forEachIndexed { index, label ->
                    val selected = tab == index
                    Text(
                        "$label (0)",
                        color = if (selected) NostrordColors.Success else NostrordColors.TextMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier =
                        Modifier
                            .clip(NostrordShapes.shapeSmall)
                            .clickable { tab = index }
                            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                    )
                }
            }
            HorizontalDivider(color = NostrordColors.Divider)

            // Conversation list arrives with the DM backend (NIP-17).
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "No conversations yet",
                    color = NostrordColors.TextMuted,
                    fontSize = 13.sp,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    "Start a conversation",
                    color = NostrordColors.Success,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier =
                    Modifier
                        .clip(NostrordShapes.shapeSmall)
                        .clickable { searchOpen = true }
                        .padding(horizontal = Spacing.xs, vertical = Spacing.xxs),
                )
            }
        }
    }
}

@Composable
private fun StartConversationRow(
    pubkeyHex: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val npub = remember(pubkeyHex) { runCatching { Nip19.encodeNpub(pubkeyHex) }.getOrDefault(pubkeyHex) }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm)
            .clip(NostrordShapes.shapeMedium)
            .background(if (isHovered) NostrordColors.HoverBackground else androidx.compose.ui.graphics.Color.Transparent)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs + Spacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        OptimizedSmallAvatar(
            imageUrl = null,
            identifier = pubkeyHex,
            displayName = npub,
            size = 28.dp,
            shape = CircleShape,
        )
        Text(
            npub,
            color = NostrordColors.TextSecondary,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
