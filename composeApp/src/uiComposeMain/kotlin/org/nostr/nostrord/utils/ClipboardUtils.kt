package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable

/**
 * Provides a clipboard writer function for copying text.
 * Uses platform-specific implementations to avoid deprecated LocalClipboardManager.
 */
@Composable
expect fun rememberClipboardWriter(): (String) -> Unit
