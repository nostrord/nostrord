package org.nostr.nostrord.utils

import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.file.StandardOpenOption

/**
 * Single-instance guard + deep-link forwarding channel for the desktop app.
 *
 * The primary instance holds an exclusive FileLock on ~/.nostrord/instance.lock
 * for its whole lifetime and listens on an ephemeral loopback port (written to
 * instance.port). A second launch, e.g. the OS invoking the nostrord:// handler
 * while the app is open, fails the lock, forwards its argv line over the socket
 * and exits, so the running window routes the deep link instead of a second
 * full app (second relay pool, second passphrase gate) spawning.
 */
object SingleInstance {
    // Held (never released) while the primary lives; the OS drops it on exit.
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /**
     * Returns true when this process is the primary and should start the UI.
     * Returns false after forwarding [args] to the running instance; the caller
     * must exit without touching AWT. [onMessage] receives each forwarded line
     * (the deep-link URI, or "" for a plain focus request) on a daemon thread.
     */
    fun acquireOrForward(
        args: Array<String>,
        dir: File = File(System.getProperty("user.home"), ".nostrord"),
        onMessage: (String) -> Unit,
    ): Boolean {
        dir.mkdirs()
        val portFile = File(dir, "instance.port")
        val acquired =
            try {
                val channel =
                    FileChannel.open(
                        File(dir, "instance.lock").toPath(),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE,
                    )
                channel.tryLock().also { if (it == null) channel.close() }
            } catch (_: Exception) {
                null
            }
        if (acquired != null) {
            lock = acquired
            try {
                listen(portFile, onMessage)
            } catch (e: Exception) {
                // Focus/deep-link forwarding is best-effort; the app still runs without it.
                System.err.println("[nostrord] single-instance listener unavailable: $e")
            }
            return true
        }
        return !forward(portFile, args.firstOrNull().orEmpty())
    }

    private fun listen(portFile: File, onMessage: (String) -> Unit) {
        val ss = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = ss
        portFile.writeText(ss.localPort.toString())
        Thread {
            while (!ss.isClosed) {
                try {
                    ss.accept().use { socket ->
                        socket.soTimeout = 2_000
                        val line = socket.getInputStream().bufferedReader().readLine() ?: return@use
                        onMessage(line)
                    }
                } catch (_: Exception) {
                    // A bad client write must not kill the listener.
                }
            }
        }.apply {
            isDaemon = true
            name = "nostrord-single-instance"
            start()
        }
    }

    /** true when the message reached the primary; false falls back to running standalone
     *  (stale lock holder without a listener beats refusing to start at all). */
    private fun forward(portFile: File, message: String): Boolean = try {
        val port = portFile.readText().trim().toInt()
        Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
            socket.getOutputStream().apply {
                write((message + "\n").encodeToByteArray())
                flush()
            }
        }
        true
    } catch (_: Exception) {
        false
    }
}
