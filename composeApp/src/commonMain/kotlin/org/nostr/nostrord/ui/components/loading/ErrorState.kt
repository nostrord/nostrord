package org.nostr.nostrord.ui.components.loading

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Error state display with retry button.
 * Used when content fails to load.
 */
@Composable
fun ErrorState(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Default.Warning,
    retryText: String = "Retry"
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = NostrordColors.Error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = NostrordColors.Primary
            ),
            shape = RoundedCornerShape(8.dp)
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(retryText)
        }
    }
}

/**
 * Connection error state with specific messaging.
 */
@Composable
fun ConnectionErrorState(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    ErrorState(
        message = "Unable to connect to relay.\nCheck your internet connection and try again.",
        onRetry = onRetry,
        icon = Icons.Default.WifiOff,
        retryText = "Reconnect",
        modifier = modifier
    )
}

/**
 * Shown when a relay returns "restricted" — access is permanently denied.
 * No retry button because the restriction won't change without relay-side action.
 */
@Composable
fun RestrictedRelayState(
    message: String,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Block,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = NostrordColors.Error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Access denied by relay",
            color = NostrordColors.TextPrimary,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = message,
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * Empty state display when there's no content.
 */
@Composable
fun EmptyState(
    message: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    action: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = NostrordColors.TextMuted
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        Text(
            text = message,
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )

        if (action != null) {
            Spacer(modifier = Modifier.height(24.dp))
            action()
        }
    }
}

/**
 * Inline retry button for use within lists.
 */
@Composable
fun InlineRetryButton(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = message,
            color = NostrordColors.TextMuted,
            style = MaterialTheme.typography.bodySmall
        )
        Spacer(modifier = Modifier.width(12.dp))
        TextButton(
            onClick = onRetry,
            colors = ButtonDefaults.textButtonColors(
                contentColor = NostrordColors.Primary
            )
        ) {
            Icon(
                Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text("Retry", style = MaterialTheme.typography.labelMedium)
        }
    }
}
