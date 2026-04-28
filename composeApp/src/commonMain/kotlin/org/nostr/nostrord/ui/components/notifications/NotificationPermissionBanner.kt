package org.nostr.nostrord.ui.components.notifications

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.notifications.NotificationPermission
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.ui.theme.NostrordColors

private const val DISMISSED_PREF_KEY = "notif_banner_dismissed"

@Composable
fun NotificationPermissionBanner(modifier: Modifier = Modifier) {
    val service = AppModule.notificationService
    if (!service.isSupported()) return

    var dismissed by remember {
        mutableStateOf(SecureStorage.getBooleanPref(DISMISSED_PREF_KEY, false))
    }
    val permission by service.permission.collectAsState()

    if (dismissed || permission != NotificationPermission.Default) return

    Card(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .widthIn(max = 520.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Notifications,
                contentDescription = null,
                tint = NostrordColors.Primary,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = "Enable desktop notifications for new messages?",
                color = NostrordColors.TextPrimary,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = { service.requestPermission() }) {
                Text("Enable", color = NostrordColors.Primary)
            }
            TextButton(
                onClick = {
                    SecureStorage.saveBooleanPref(DISMISSED_PREF_KEY, true)
                    dismissed = true
                },
                contentPadding = PaddingValues(horizontal = 8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = NostrordColors.TextMuted,
                )
            }
        }
    }
}
