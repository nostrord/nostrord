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
import kotlinx.atomicfu.atomic
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.nostr.nostrord.nostr.secureRandomBytes
import platform.Foundation.NSURL
import platform.SafariServices.SFSafariViewController
import platform.SafariServices.SFSafariViewControllerDelegateProtocol
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController
import platform.darwin.NSObject
import platform.posix.AF_INET
import platform.posix.SHUT_RDWR
import platform.posix.SOCK_STREAM
import platform.posix.SOL_SOCKET
import platform.posix.SO_NOSIGPIPE
import platform.posix.accept
import platform.posix.bind
import platform.posix.close
import platform.posix.errno
import platform.posix.getsockname
import platform.posix.listen
import platform.posix.memset
import platform.posix.recv
import platform.posix.send
import platform.posix.setsockopt
import platform.posix.shutdown
import platform.posix.sockaddr_in
import platform.posix.socket
import platform.posix.socklen_tVar

/**
 * iOS auth via the same loopback-opener pattern as desktop/Android: Google refuses OAuth from
 * embedded webviews (WKWebView included), so we serve the shared opener page on
 * 127.0.0.1:<random> and open it in SFSafariViewController (real Safari, the user's Google
 * session, window.open works). The captured token/shard POSTs back to a single-use loopback
 * path; on completion the host disposes this composable and the sheet is dismissed, so no
 * deep-link return is needed. ATS does not apply: Safari, not the app, fetches the page.
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
internal actual fun PomegranateAuthWebView(
    url: String,
    expectedOrigin: String,
    mode: PomegranateAuthBridge.Mode,
    onResult: (String) -> Unit,
    onError: (Throwable) -> Unit,
) {
    DisposableEffect(url) {
        val server = IosLoopbackServer(url, expectedOrigin, mode, onResult)
        val timeoutScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        var safari: SFSafariViewController? = null
        // Strong ref: SFSafariViewController.delegate is weak.
        var delegate: SafariDismissDelegate?
        try {
            val port = server.start()
            val pageUrl = NSURL.URLWithString("http://127.0.0.1:$port/")
            val presenter = topPresenter()
            if (pageUrl == null || presenter == null) {
                onError(RuntimeException("Cannot open the Google sign-in page"))
            } else {
                val vc = SFSafariViewController(uRL = pageUrl)
                delegate = SafariDismissDelegate {
                    // User tapped Done before any result: a cancel, not an error.
                    if (!server.delivered.value) PomegranateAuthBridge.cancel()
                }
                vc.delegate = delegate
                safari = vc
                presenter.presentViewController(vc, animated = true, completion = null)
                timeoutScope.launch {
                    delay(POPUP_TIMEOUT_MS)
                    if (!server.delivered.value) onError(RuntimeException("Timed out waiting for Google sign-in"))
                    server.stop()
                }
            }
        } catch (t: Throwable) {
            onError(t)
        }
        onDispose {
            timeoutScope.cancel()
            server.stop()
            safari?.dismissViewControllerAnimated(true, completion = null)
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "A sign-in page opened in Safari. Finish signing in with Google there, then return here.",
            style = MaterialTheme.typography.bodyMedium,
        )
        CircularProgressIndicator(modifier = Modifier.width(28.dp))
    }
}

private const val POPUP_TIMEOUT_MS = 5L * 60L * 1000L

private class SafariDismissDelegate(
    private val onFinish: () -> Unit,
) : NSObject(),
    SFSafariViewControllerDelegateProtocol {
    override fun safariViewControllerDidFinish(controller: SFSafariViewController) {
        onFinish()
    }
}

/** Topmost presented view controller, the only place a modal can be presented from. */
private fun topPresenter(): UIViewController? {
    var top = UIApplication.sharedApplication.keyWindow?.rootViewController
    while (true) {
        top = top?.presentedViewController ?: return top
    }
}

/**
 * Minimal loopback HTTP/1.1 responder (no java.net on Kotlin/Native) bound to 127.0.0.1 on a
 * random port via POSIX sockets. Serves the shared opener page at `/` and accepts the captured
 * value at the single-use random `/cb/<token>` path, then shuts down. Connection: close, one
 * request per socket. SO_NOSIGPIPE on every fd: an unhandled SIGPIPE kills an iOS app.
 */
@OptIn(ExperimentalForeignApi::class)
private class IosLoopbackServer(
    private val centralUrl: String,
    private val expectedOrigin: String,
    private val mode: PomegranateAuthBridge.Mode,
    private val onResult: (String) -> Unit,
) {
    val delivered = atomic(false)
    private val running = atomic(true)
    private val callbackPath = pomegranateCallbackPath(secureRandomBytes(16))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serverFd = atomic(-1)

    fun start(): Int {
        val fd = socket(AF_INET, SOCK_STREAM, 0)
        check(fd >= 0) { "socket() failed: errno $errno" }
        serverFd.value = fd
        val port =
            memScoped {
                disableSigpipe(fd)
                val addr = alloc<sockaddr_in>()
                memset(addr.ptr, 0, sizeOf<sockaddr_in>().convert())
                addr.sin_family = AF_INET.convert()
                // 127.0.0.1 in network byte order as stored on little-endian arm64
                // (inet_addr is not exposed by the posix cinterop on iOS).
                addr.sin_addr.s_addr = 0x0100007fu
                addr.sin_port = 0.convert()
                check(bind(fd, addr.ptr.reinterpret(), sizeOf<sockaddr_in>().convert()) == 0) { "bind() failed: errno $errno" }
                check(listen(fd, 8) == 0) { "listen() failed: errno $errno" }
                val bound = alloc<sockaddr_in>()
                val len = alloc<socklen_tVar>().apply { value = sizeOf<sockaddr_in>().convert() }
                check(getsockname(fd, bound.ptr.reinterpret(), len.ptr) == 0) { "getsockname() failed: errno $errno" }
                // sin_port is network byte order.
                val raw = bound.sin_port.toInt() and 0xffff
                ((raw and 0xff) shl 8) or ((raw shr 8) and 0xff)
            }
        scope.launch { acceptLoop() }
        return port
    }

    fun stop() {
        if (!running.compareAndSet(expect = true, update = false)) return
        val fd = serverFd.getAndSet(-1)
        if (fd >= 0) {
            shutdown(fd, SHUT_RDWR)
            close(fd)
        }
        scope.cancel()
    }

    private fun acceptLoop() {
        while (running.value) {
            val client = accept(serverFd.value, null, null)
            if (client < 0) break
            scope.launch { handle(client) }
        }
    }

    private fun handle(fd: Int) {
        try {
            disableSigpipe(fd)
            val request = readRequest(fd) ?: return
            val (method, path, body) = request
            when {
                method == "GET" && path == "/" -> {
                    val html =
                        pomegranateOpenerPageHtml(
                            centralUrl,
                            expectedOrigin,
                            mode,
                            callbackPath,
                            // The app is right behind the Safari sheet; dismissal is the return.
                            returnDeepLink = null,
                            returnIntentUrl = null,
                        )
                    respond(fd, 200, "text/html; charset=utf-8", html.encodeToByteArray())
                }
                method == "POST" && path == callbackPath -> {
                    respond(fd, 200, "text/plain", "ok".encodeToByteArray())
                    val value = body.decodeToString()
                    if (value.isNotEmpty() && delivered.compareAndSet(expect = false, update = true)) onResult(value)
                    stop()
                }
                else -> respond(fd, 404, "text/plain", "not found".encodeToByteArray())
            }
        } finally {
            close(fd)
        }
    }

    /** Reads one request; null on a malformed or oversized one. */
    private fun readRequest(fd: Int): Triple<String, String, ByteArray>? {
        val buf = ByteArray(8192)
        var data = ByteArray(0)
        var headerEnd: Int
        while (true) {
            headerEnd = data.indexOfHeaderEnd()
            if (headerEnd >= 0) break
            if (data.size > MAX_REQUEST_BYTES) return null
            val n = buf.usePinned { recv(fd, it.addressOf(0), buf.size.convert(), 0) }.toInt()
            if (n <= 0) return null
            data += buf.copyOf(n)
        }
        val head = data.copyOf(headerEnd).decodeToString()
        val lines = head.split("\r\n")
        val requestLine = lines.first().split(" ")
        val method = requestLine.getOrNull(0) ?: return null
        val path = requestLine.getOrNull(1) ?: return null
        val contentLength =
            lines.drop(1)
                .firstOrNull { it.startsWith("Content-Length", ignoreCase = true) }
                ?.substringAfter(':')?.trim()?.toIntOrNull() ?: 0
        if (contentLength > MAX_REQUEST_BYTES) return null
        var body = data.copyOfRange(headerEnd + 4, data.size)
        while (body.size < contentLength) {
            val n = buf.usePinned { recv(fd, it.addressOf(0), buf.size.convert(), 0) }.toInt()
            if (n <= 0) break
            body += buf.copyOf(n)
        }
        return Triple(method, path, body.copyOf(minOf(contentLength, body.size)))
    }

    private fun respond(
        fd: Int,
        status: Int,
        contentType: String,
        body: ByteArray,
    ) {
        val header =
            "HTTP/1.1 $status OK\r\nContent-Type: $contentType\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        sendAll(fd, header.encodeToByteArray() + body)
    }

    private fun sendAll(fd: Int, bytes: ByteArray) {
        var off = 0
        bytes.usePinned { pinned ->
            while (off < bytes.size) {
                val n = send(fd, pinned.addressOf(off), (bytes.size - off).convert(), 0).toInt()
                if (n <= 0) return
                off += n
            }
        }
    }

    private fun disableSigpipe(fd: Int) {
        memScoped {
            val one = alloc<IntVar>().apply { value = 1 }
            setsockopt(fd, SOL_SOCKET, SO_NOSIGPIPE, one.ptr, sizeOf<IntVar>().convert())
        }
    }

    private fun ByteArray.indexOfHeaderEnd(): Int {
        for (i in 0..size - 4) {
            if (this[i] == CR && this[i + 1] == LF && this[i + 2] == CR && this[i + 3] == LF) return i
        }
        return -1
    }

    private companion object {
        const val MAX_REQUEST_BYTES = 64 * 1024
        const val CR = '\r'.code.toByte()
        const val LF = '\n'.code.toByte()
    }
}
