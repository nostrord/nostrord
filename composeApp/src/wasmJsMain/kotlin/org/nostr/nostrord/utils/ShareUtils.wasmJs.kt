package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import kotlinx.coroutines.launch

actual val supportsNativeShare: Boolean = false

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun rememberTextSharer(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { text ->
        scope.launch { clipboard.setClipEntry(ClipEntry.withPlainText(text)) }
    }
}
