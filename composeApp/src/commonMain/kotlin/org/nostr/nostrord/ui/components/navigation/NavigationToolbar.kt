package org.nostr.nostrord.ui.components.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

@Composable
fun NavigationToolbar(
    canGoBack: Boolean,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(NostrordColors.BackgroundDark),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack, enabled = canGoBack) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = if (canGoBack) NostrordColors.TextPrimary else NostrordColors.TextMuted
            )
        }
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = "Forward",
                tint = if (canGoForward) NostrordColors.TextPrimary else NostrordColors.TextMuted
            )
        }
    }
}
