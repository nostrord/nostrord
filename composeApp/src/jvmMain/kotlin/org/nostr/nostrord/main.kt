package org.nostr.nostrord

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.toPainter
import javax.imageio.ImageIO
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpRedirect
import io.ktor.client.plugins.HttpTimeout
import okio.Path.Companion.toOkioPath
import org.nostr.nostrord.startup.ExternalLaunchContext
import org.nostr.nostrord.startup.StartupResolver
import org.nostr.nostrord.ui.window.DesktopWindowControls
import org.nostr.nostrord.ui.window.LocalAwtWindow
import org.nostr.nostrord.ui.window.LocalDesktopWindowControls
import java.io.File
import java.net.URI

/**
 * Parse a nostrord:// or https:// URL into an ExternalLaunchContext.
 * Supports: nostrord://open?relay=X&group=Y&code=Z
 *           https://nostrord.com/open/?relay=X&group=Y&code=Z
 */
private fun parseDeepLinkUrl(url: String): ExternalLaunchContext? {
    val uri = try { URI(url) } catch (_: Exception) { return null }
    val query = uri.query ?: return null
    val params = query.split("&").associate { param ->
        val idx = param.indexOf("=")
        if (idx >= 0) param.substring(0, idx) to java.net.URLDecoder.decode(param.substring(idx + 1), "UTF-8")
        else param to ""
    }
    val relay = params["relay"]?.takeIf { it.isNotBlank() } ?: return null
    val relayUrl = if ("://" in relay) relay else "wss://$relay"
    val groupId = params["group"]?.takeIf { it.isNotBlank() }
    val inviteCode = params["code"]?.takeIf { it.isNotBlank() }

    return if (groupId != null) {
        ExternalLaunchContext.OpenGroup(groupId = groupId, groupName = null, relayUrl = relayUrl, inviteCode = inviteCode)
    } else {
        ExternalLaunchContext.OpenRelay(relayUrl)
    }
}

fun main(args: Array<String> = emptyArray()) {
    // Parse deep link from CLI args (e.g., OS protocol handler passes URL as first arg)
    args.firstOrNull()?.let { url ->
        parseDeepLinkUrl(url)?.let { StartupResolver.setExternalLaunchContext(it) }
    }
    // Configure Coil before the window opens so the first frame is already using the
    // persistent cache. Without this, Coil defaults to a temp-dir disk cache (wiped on
    // reboot) and a short-lived Ktor client — causing relay icons to flicker on cold starts.
    SingletonImageLoader.setSafe { context ->
        val cacheDir = File(System.getProperty("user.home"), ".nostrord/image-cache")
        val httpClient = HttpClient(CIO) {
            install(HttpTimeout) {
                connectTimeoutMillis = 5_000
                requestTimeoutMillis = 15_000
                socketTimeoutMillis = 10_000
            }
            install(HttpRedirect) {
                checkHttpMethod = false
            }
        }
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient))
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizeBytes(128L * 1024 * 1024) // 128 MB — desktop has more RAM
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.toOkioPath())
                    .maxSizeBytes(256L * 1024 * 1024) // 256 MB persistent cache
                    .build()
            }
            .build()
    }

    application {
    val windowState = rememberWindowState(
        width = 1280.dp,
        height = 800.dp,
        position = WindowPosition.Aligned(Alignment.Center)
    )

    Window(
        onCloseRequest = ::exitApplication,
        title = "Nostrord",
        icon = ImageIO.read(
            Thread.currentThread().contextClassLoader
                .getResourceAsStream("icon-512.png")
        ).toPainter(),
        state = windowState,
        undecorated = true,
        onPreviewKeyEvent = { event ->
            if (event.type == KeyEventType.KeyDown) {
                // Ctrl+Q (Windows/Linux) or Cmd+Q (macOS) - quit application
                if (event.key == Key.Q && (event.isCtrlPressed || event.isMetaPressed)) {
                    exitApplication()
                    true
                } else {
                    false
                }
            } else {
                false
            }
        }
    ) {
        val controls = remember(windowState) {
            object : DesktopWindowControls {
                override fun minimize() { windowState.isMinimized = true }
                override fun toggleMaximize() {
                    windowState.placement = if (windowState.placement == WindowPlacement.Maximized)
                        WindowPlacement.Floating else WindowPlacement.Maximized
                }
                override fun close() { exitApplication() }
                override val isMaximized: Boolean
                    get() = windowState.placement == WindowPlacement.Maximized
            }
        }

        CompositionLocalProvider(
            LocalDesktopWindowControls provides controls,
            LocalAwtWindow provides window
        ) {
            App()
        }
    }
    }
}
