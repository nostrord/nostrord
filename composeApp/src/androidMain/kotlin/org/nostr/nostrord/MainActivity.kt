package org.nostr.nostrord

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.toRelayUrl

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // App is dark-only — pin system bar icons to light regardless of OS theme.
        val barStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            Box(modifier = Modifier.fillMaxSize().background(NostrordColors.Background)) {
                Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                    App()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val relay = uri.getQueryParameter("relay")?.takeIf { it.isNotBlank() } ?: return
        val relayUrl = relay.toRelayUrl().takeIf { it.isNotBlank() } ?: return
        val groupId = uri.getQueryParameter("group")?.takeIf { it.isNotBlank() }
        val inviteCode = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
        val messageId = uri.getQueryParameter("message")?.takeIf { it.isNotBlank() }

        val context = if (groupId != null) {
            ExternalLaunchContext.OpenGroup(
                groupId = groupId,
                groupName = null,
                relayUrl = relayUrl,
                inviteCode = inviteCode,
                messageId = messageId
            )
        } else {
            ExternalLaunchContext.OpenRelay(relayUrl)
        }
        StartupResolver.setExternalLaunchContext(context)
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
