package org.nostr.nostrord.utils

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

actual val supportsNativeShare: Boolean = true

@Composable
actual fun rememberTextSharer(): (String) -> Unit {
    val context = LocalContext.current
    return { text ->
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}
