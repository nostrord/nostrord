package org.nostr.nostrord.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cached
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import org.nostr.nostrord.ui.pubkeyIdentifiers
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.utils.rememberClipboardWriter

/**
 * Cycling pubkey identifier field (prototype IdentifierField): the swap button
 * rotates npub / nprofile / nostrord link / hex / nip-05 and the copy button
 * copies the visible value (checkmark feedback). Used by the profile modal and
 * the profile page; mirrors the web components/IdentifierField.
 */
@Composable
fun IdentifierField(
    pubkey: String,
    nip05: String? = null,
    modifier: Modifier = Modifier,
) {
    val ids = remember(pubkey, nip05) { pubkeyIdentifiers(pubkey, nip05) }
    if (ids.isEmpty()) return
    var index by remember(pubkey) { mutableStateOf(0) }
    var copied by remember { mutableStateOf(false) }
    val copyToClipboard = rememberClipboardWriter()
    val id = ids[index % ids.size]

    LaunchedEffect(copied) {
        if (copied) {
            delay(1200)
            copied = false
        }
    }

    Column(modifier = modifier) {
        Surface(shape = NostrordShapes.shapeMedium, color = NostrordColors.BackgroundFloating) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Spacing.md),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xxs),
            ) {
                Text(
                    id.value,
                    color = NostrordColors.TextMuted,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { index = (index + 1) % ids.size }) {
                    Icon(
                        imageVector = Icons.Default.Cached,
                        contentDescription = "Format: ${id.label} (switch)",
                        tint = NostrordColors.TextMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
                IconButton(onClick = {
                    copyToClipboard(id.value)
                    copied = true
                }) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        tint = if (copied) NostrordColors.Success else NostrordColors.TextMuted,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(Spacing.xxs))
        Row(modifier = Modifier.padding(horizontal = Spacing.xs)) {
            Text("Format: ", color = NostrordColors.TextMuted, fontSize = 11.sp)
            Text(
                id.label,
                color = NostrordColors.TextSecondary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
