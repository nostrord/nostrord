package org.nostr.nostrord.network.upload

import androidx.compose.runtime.Composable

@Composable
actual fun ShareMediaEffect(onMediaPasted: (ByteArray, String) -> Unit, onError: (String) -> Unit) {}
