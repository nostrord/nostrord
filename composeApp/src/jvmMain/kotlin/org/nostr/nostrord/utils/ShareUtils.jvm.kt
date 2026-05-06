package org.nostr.nostrord.utils

import androidx.compose.runtime.Composable
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection

actual val supportsNativeShare: Boolean = false

@Composable
actual fun rememberTextSharer(): (String) -> Unit = { text ->
    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
    clipboard.setContents(StringSelection(text), null)
}
