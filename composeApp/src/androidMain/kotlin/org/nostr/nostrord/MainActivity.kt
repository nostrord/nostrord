package org.nostr.nostrord

import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import org.nostr.nostrord.network.upload.ShareMediaQueue
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.ui.components.media.FullscreenVideoController
import org.nostr.nostrord.ui.components.media.FullscreenVideoOverlay
import org.nostr.nostrord.ui.components.media.LocalFullscreenVideoController
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.toRelayUrl

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // App is dark-only — pin system bar icons to light regardless of OS theme.
        val barStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        enableEdgeToEdge(statusBarStyle = barStyle, navigationBarStyle = barStyle)
        super.onCreate(savedInstanceState)
        handleDeepLink(intent)
        handleShareIntent(intent)
        setContent {
            val fullscreenVideoController = remember { FullscreenVideoController() }
            CompositionLocalProvider(LocalFullscreenVideoController provides fullscreenVideoController) {
                Box(modifier = Modifier.fillMaxSize().background(NostrordColors.Background)) {
                    Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                        App()
                    }
                    FullscreenVideoOverlay(fullscreenVideoController)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDeepLink(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        @Suppress("DEPRECATION")
        val uris: List<Uri> =
            when (intent.action) {
                Intent.ACTION_SEND ->
                    listOfNotNull(
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            intent.getParcelableExtra(Intent.EXTRA_STREAM)
                        },
                    )
                Intent.ACTION_SEND_MULTIPLE ->
                    (
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                        }
                        )?.toList() ?: emptyList()
                else -> emptyList()
            }
        if (uris.isNotEmpty()) ShareMediaQueue.offer(uris)
    }

    private fun handleDeepLink(intent: Intent?) {
        val uri = intent?.data ?: return
        val relay = uri.getQueryParameter("relay")?.takeIf { it.isNotBlank() } ?: return
        val relayUrl = relay.toRelayUrl().takeIf { it.isNotBlank() } ?: return
        val groupId = uri.getQueryParameter("group")?.takeIf { it.isNotBlank() }
        val inviteCode = uri.getQueryParameter("code")?.takeIf { it.isNotBlank() }
        val messageId = uri.getQueryParameter("e")?.takeIf { it.isNotBlank() }

        val context =
            if (groupId != null) {
                ExternalLaunchContext.OpenGroup(
                    groupId = groupId,
                    groupName = null,
                    relayUrl = relayUrl,
                    inviteCode = inviteCode,
                    messageId = messageId,
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
