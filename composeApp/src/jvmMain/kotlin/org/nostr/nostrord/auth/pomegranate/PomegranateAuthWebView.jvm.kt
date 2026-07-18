package org.nostr.nostrord.auth.pomegranate

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
import androidx.compose.ui.unit.dp
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.awt.Desktop
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.security.SecureRandom
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Desktop auth via a loopback opener page in the user's real browser. Google refuses OAuth from
 * the embedded JavaFX WebKit engine, so we serve a tiny page on 127.0.0.1:<random> that the
 * system browser opens; a click there does window.open(centralUrl) so the central page's native
 * window.opener.postMessage works, and the page POSTs the captured token/shard back to a
 * single-use loopback path. Bonus: the browser already holds the user's Google session.
 */
@Composable
internal actual fun PomegranateAuthWebView(
    url: String,
    expectedOrigin: String,
    mode: PomegranateAuthBridge.Mode,
    onResult: (String) -> Unit,
    onError: (Throwable) -> Unit,
) {
    DisposableEffect(url) {
        val server = LoopbackAuthServer(url, expectedOrigin, mode, onResult)
        val timeout = Timer(true)
        try {
            val port = server.start()
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI("http://127.0.0.1:$port/"))
            } else {
                onError(RuntimeException("No default browser available for Google sign-in"))
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
            "A sign-in page opened in your browser. Finish signing in with Google there, then return here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        CircularProgressIndicator(modifier = Modifier.width(28.dp))
    }
}

private const val POPUP_TIMEOUT_MS = 5L * 60L * 1000L

/**
 * Loopback HTTP server bound to 127.0.0.1 on a random port. Serves the opener page at / and
 * accepts the captured value at a single-use random /cb/<token> path (so no other local process
 * can post a fake token), then shuts down.
 */
private class LoopbackAuthServer(
    private val centralUrl: String,
    private val expectedOrigin: String,
    private val mode: PomegranateAuthBridge.Mode,
    private val onResult: (String) -> Unit,
) {
    val delivered = AtomicBoolean(false)
    private val callbackPath = pomegranateCallbackPath(randomBytes())
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)

    fun start(): Int {
        server.createContext("/") { exchange -> handleRoot(exchange) }
        server.createContext("/cb/") { exchange -> handleCallback(exchange) }
        server.executor = null
        server.start()
        return server.address.port
    }

    fun stop() {
        thread(isDaemon = true) { server.stop(0) }
    }

    private fun handleRoot(exchange: HttpExchange) {
        // Desktop returns via window.close (Chromium) or the confirmation fallback, no deep link.
        val html =
            pomegranateOpenerPageHtml(
                centralUrl,
                expectedOrigin,
                mode,
                callbackPath,
                returnDeepLink = null,
                returnIntentUrl = null,
            )
        val body = html.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }

    private fun handleCallback(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod == "POST" && exchange.requestURI.path == callbackPath) {
                val value = exchange.requestBody.readBytes().decodeToString()
                val reply = "ok".encodeToByteArray()
                exchange.sendResponseHeaders(200, reply.size.toLong())
                exchange.responseBody.use { it.write(reply) }
                if (value.isNotEmpty() && delivered.compareAndSet(false, true)) onResult(value)
                stop()
            } else {
                exchange.sendResponseHeaders(404, -1)
                exchange.close()
            }
        } catch (_: Throwable) {
            exchange.close()
        }
    }

    private fun randomBytes(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
}
