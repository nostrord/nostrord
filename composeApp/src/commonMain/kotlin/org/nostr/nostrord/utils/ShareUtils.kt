package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable

expect val supportsNativeShare: Boolean

@Composable
expect fun rememberTextSharer(): (String) -> Unit
