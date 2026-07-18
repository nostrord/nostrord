package org.nostr.nostrord.auth.pomegranate

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Android auth via the same loopback-opener pattern as desktop: Google refuses OAuth from the
 * embedded WebView (its UA carries "; wv"), so we serve a one-page opener on 127.0.0.1:<random>
 * and open it in Chrome Custom Tabs (the real browser, with the user's Google session). The page
 * does window.open(centralUrl); the central page's native window.opener.postMessage is captured
 * and POSTed back to a single-use loopback path, then a `nostrord://open` deep link bounces back
 * to the app. com.sun.net.httpserver is absent on Android, so this uses a minimal ServerSocket.
 */
@Composable
internal actual fun PomegranateAuthWebView(
    url: String,
    expectedOrigin: String,
    mode: PomegranateAuthBridge.Mode,
    onResult: (String) -> Unit,
    onError: (Throwable) -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(url) {
        val server = AndroidLoopbackServer(url, expectedOrigin, mode, onResult)
        val timeout = Timer(true)
        try {
            val port = server.start()
            try {
                CustomTabsIntent.Builder().build().launchUrl(context, Uri.parse("http://127.0.0.1:$port/"))
            } catch (_: ActivityNotFoundException) {
                onError(RuntimeException("No browser available for Google sign-in"))
            }
            timeout.schedule(
                object : TimerTask() {
                    override fun run() {
                        if (!server.delivered.get()) onError(RuntimeException("Timed out waiting for Google sign-in"))
                        server.stop()
                    }
                },
                POPUP_TIMEOUT_MS,
            )
        } catch (t: Throwable) {
            onError(t)
        }
        onDispose {
            timeout.cancel()
            server.stop()
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "A sign-in page opened in your browser. Finish signing in with Google there, then return to the app.",
            style = MaterialTheme.typography.bodyMedium,
        )
        CircularProgressIndicator(modifier = Modifier.width(28.dp))
    }
}

private const val POPUP_TIMEOUT_MS = 5L * 60L * 1000L

/**
 * Minimal loopback HTTP/1.1 responder (Android has no com.sun.net.httpserver) bound to 127.0.0.1
 * on a random port. Serves the shared opener page at `/` and accepts the captured value at the
 * single-use random `/cb/<token>` path, then shuts down. Connection: close, one request per socket.
 */
private class AndroidLoopbackServer(
    private val centralUrl: String,
    private val expectedOrigin: String,
    private val mode: PomegranateAuthBridge.Mode,
    private val onResult: (String) -> Unit,
) {
    val delivered = AtomicBoolean(false)
    private val callbackPath = pomegranateCallbackPath(ByteArray(16).also { SecureRandom().nextBytes(it) })
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))

    @Volatile private var running = true

    fun start(): Int {
        thread(isDaemon = true) { acceptLoop() }
        return serverSocket.localPort
    }

    fun stop() {
        running = false
        try {
            serverSocket.close()
        } catch (_: Throwable) {
        }
    }

    private fun acceptLoop() {
        while (running) {
            val socket =
                try {
                    serverSocket.accept()
                } catch (_: Throwable) {
                    break
                }
            thread(isDaemon = true) { handle(socket) }
        }
    }

    private fun handle(socket: Socket) {
        socket.use {
            val reader = it.getInputStream().bufferedReader()
            val out = it.getOutputStream()
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            val method = parts.getOrNull(0).orEmpty()
            val path = parts.getOrNull(1).orEmpty()

            var contentLength = 0
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                val colon = line.indexOf(':')
                if (colon > 0 && line.substring(0, colon).trim().equals("Content-Length", ignoreCase = true)) {
                    contentLength = line.substring(colon + 1).trim().toIntOrNull() ?: 0
                }
            }

            when {
                method == "GET" && path == "/" -> {
                    val html =
                        pomegranateOpenerPageHtml(
                            centralUrl,
                            expectedOrigin,
                            mode,
                            callbackPath,
                            returnDeepLink = "nostrord://open",
                            // Chrome honors the intent:// form more readily than a bare custom scheme.
                            returnIntentUrl = "intent://open#Intent;scheme=nostrord;package=org.nostr.nostrord;end",
                        )
                    respond(out, 200, "text/html; charset=utf-8", html.encodeToByteArray())
                }
                method == "POST" && path == callbackPath -> {
                    val body = CharArray(contentLength)
                    var read = 0
                    while (read < contentLength) {
                        val r = reader.read(body, read, contentLength - read)
                        if (r < 0) break
                        read += r
                    }
                    val value = String(body, 0, read)
                    respond(out, 200, "text/plain", "ok".encodeToByteArray())
                    if (value.isNotEmpty() && delivered.compareAndSet(false, true)) onResult(value)
                    stop()
                }
                else -> respond(out, 404, "text/plain", "not found".encodeToByteArray())
            }
        }
    }

    private fun respond(
        out: OutputStream,
        status: Int,
        contentType: String,
        body: ByteArray,
    ) {
        val header =
            "HTTP/1.1 $status OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        out.write(header.encodeToByteArray())
        out.write(body)
        out.flush()
    }
}
