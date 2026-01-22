package org.nostr.nostrord.utils

import android.content.ClipData
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import kotlinx.coroutines.launch

@Composable
actual fun rememberClipboardWriter(): (String) -> Unit {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    return { text ->
        scope.launch {
            val clipData = ClipData.newPlainText("nostrord", text)
            clipboard.setClipEntry(clipData.toClipEntry())
        }
    }
}
