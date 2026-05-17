package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable

@Composable
expect fun ShareMediaEffect(
    onMediaPasted: (ByteArray, String) -> Unit,
    onError: (String) -> Unit = {},
)
