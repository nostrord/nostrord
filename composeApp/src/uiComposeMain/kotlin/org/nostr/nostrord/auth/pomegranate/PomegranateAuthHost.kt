package org.nostr.nostrord.auth.pomegranate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.nostr.nostrord.ui.theme.NostrordShapes

/**
 * WebView the auth flow needs, per platform: loads [url], injects [pomegranateOpenerInjection]
 * so the page's postMessage reaches native, and reports the captured token/shard via [onResult]
 * (or a load failure via [onError]).
 */
@Composable
internal expect fun PomegranateAuthWebView(
    url: String,
    expectedOrigin: String,
    mode: PomegranateAuthBridge.Mode,
    onResult: (String) -> Unit,
    onError: (Throwable) -> Unit,
)

/**
 * Mounts near the app root and shows the auth WebView whenever the service opens a popup.
 * Dismissing reports a cancel (a silent no-op upstream, not an error). The captured value
 * settles the suspend call in [PomegranateAuthBridge].
 */
@Composable
fun PomegranateAuthHost() {
    val request by PomegranateAuthBridge.current.collectAsState()
    val current = request ?: return

    Dialog(onDismissRequest = { PomegranateAuthBridge.cancel() }) {
        Surface(shape = NostrordShapes.shapeLarge) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sign in with Google")
                PomegranateAuthWebView(
                    url = current.url,
                    expectedOrigin = current.expectedOrigin,
                    mode = current.mode,
                    onResult = { PomegranateAuthBridge.complete(it) },
                    onError = { PomegranateAuthBridge.fail(it) },
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { PomegranateAuthBridge.cancel() }) { Text("Cancel") }
                }
            }
        }
    }
}

/** Fixed size for the embedded auth WebView; a sign-in page fits comfortably. */
internal val pomegranateWebViewModifier: Modifier = Modifier.fillMaxWidth().height(520.dp)
