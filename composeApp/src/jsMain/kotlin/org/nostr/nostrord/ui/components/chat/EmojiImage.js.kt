@file:OptIn(ExperimentalComposeUiApi::class)

package org.nostr.nostrord.ui.components.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.WebElementView
import kotlinx.browser.document
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

/**
 * JS: renders emoji as a native HTML <img> so the browser animates GIF/WebP.
 * Uses the URL directly — HTML <img> tags load cross-origin images without
 * CORS restrictions, so no CDN proxy is needed (wsrv.nl 404s on some hosts).
 * The parent SafeEmojiImage/CustomEmojiImage handles proxy retry on error.
 */
@Composable
actual fun EmojiImage(
    url: String,
    contentDescription: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onError: () -> Unit
) {
    val currentOnError by rememberUpdatedState(onError)
    val resolvedUrl = url

    WebElementView(
        factory = {
            (document.createElement("img") as HTMLImageElement).apply {
                src = resolvedUrl
                alt = contentDescription
                style.width = "100%"
                style.height = "100%"
                style.objectFit = "contain"
                style.display = "block"
                style.setProperty("pointer-events", "none")
                onerror = { _, _, _, _, _ ->
                    currentOnError()
                    (this.parentElement as? HTMLElement)?.style?.setProperty("pointer-events", "none")
                    null
                }
                onload = {
                    (this.parentElement as? HTMLElement)?.style?.setProperty("pointer-events", "none")
                    null
                }
            }
        },
        modifier = modifier,
        update = { element ->
            val img = element as HTMLImageElement
            if (img.src != resolvedUrl) {
                img.src = resolvedUrl
            }
            img.style.setProperty("pointer-events", "none")
            (img.parentElement as? HTMLElement)?.style?.setProperty("pointer-events", "none")
        }
    )
}
