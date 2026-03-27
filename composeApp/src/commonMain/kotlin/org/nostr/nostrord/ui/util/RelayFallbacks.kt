package org.nostr.nostrord.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.painter.Painter
import nostrord.composeapp.generated.resources.Res
import nostrord.composeapp.generated.resources.relay_0xchat
import nostrord.composeapp.generated.resources.relay_nip29
import org.jetbrains.compose.resources.painterResource

/**
 * Returns a bundled fallback [Painter] for relays that don't yet publish an icon
 * in their NIP-11 metadata. Returns null if no fallback is registered.
 */
@Composable
fun relayFallbackPainter(relayUrl: String): Painter? = when {
    relayUrl.contains("0xchat.com") -> painterResource(Res.drawable.relay_0xchat)
    relayUrl.contains("nip29.com") -> painterResource(Res.drawable.relay_nip29)
    else -> null
}
