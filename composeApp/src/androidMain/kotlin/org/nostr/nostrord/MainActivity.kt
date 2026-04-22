package org.nostr.nostrord

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.utils.toRelayUrl

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        setContent {
            App()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val relay = uri.getQueryParameter("relay")?.takeIf { it.isNotBlank() } ?: return
        val relayUrl = relay.toRelayUrl()
        val groupId = uri.getQueryParameter("group")?.takeIf { it.isNotBlank() }
        val inviteCode = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }

        val context = if (groupId != null) {
            ExternalLaunchContext.OpenGroup(
                groupId = groupId,
                groupName = null,
                relayUrl = relayUrl,
                inviteCode = inviteCode
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
