@file:OptIn(ExperimentalComposeUiApi::class, ExperimentalWasmJsInterop::class)

package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.WebElementView
import kotlin.js.ExperimentalWasmJsInterop
import kotlinx.browser.document
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.utils.getImageUrl
import org.w3c.dom.HTMLElement
import org.w3c.dom.HTMLImageElement

private const val IMAGE_BORDER_RADIUS_PX = 8

/**
 * Disables pointer events on [element] and its parent wrapper div.
 * WebElementView wraps our element in an absolutely-positioned div;
 * both layers must have pointer-events:none for wheel events to reach the canvas.
 */
private fun disablePointerEventsOnTree(element: HTMLElement) {
    element.style.setProperty("pointer-events", "none")
    (element.parentElement as? HTMLElement)?.style?.setProperty("pointer-events", "none")
}

/**
 * WasmJS implementation of AnimatedImage using a native HTML <img> via WebElementView.
 *
 * Scroll fix: WebElementView wraps our <img> in an absolutely-positioned <div>.
 * Both the wrapper div AND the img need pointer-events:none so wheel events
 * fall through to the Compose canvas. Click is handled by a Compose clickable
 * modifier on the parent Box (rendered on the canvas layer, below the HTML overlay).
 */
@Composable
actual fun AnimatedImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit,
    onError: () -> Unit
) {
    var aspectRatio by remember(url) { mutableStateOf(16f / 9f) }
    var isLoaded by remember(url) { mutableStateOf(false) }
    var hasError by remember(url) { mutableStateOf(false) }
    val currentOnClick by rememberUpdatedState(onClick)
    val currentOnError by rememberUpdatedState(onError)

    if (LocalAnimatedImageHidden.current) {
        Box(modifier = modifier.fillMaxWidth().aspectRatio(aspectRatio))
        return
    }

    // Error: render nothing — parent ChatImage will show text link fallback
    if (hasError) return

    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio)
            .background(NostrordColors.Surface)
            .clickable(onClick = { currentOnClick() }),
        contentAlignment = Alignment.Center
    ) {
        WebElementView(
            factory = {
                (document.createElement("img") as HTMLImageElement).apply {
                    src = getImageUrl(url)
                    style.width = "100%"
                    style.height = "100%"
                    style.objectFit = contentScale.toCssObjectFit()
                    style.borderRadius = "${IMAGE_BORDER_RADIUS_PX}px"
                    style.display = "block"
                    style.setProperty("pointer-events", "none")
                    onload = {
                        if (naturalWidth > 0 && naturalHeight > 0) {
                            aspectRatio = naturalWidth.toFloat() / naturalHeight.toFloat()
                        }
                        isLoaded = true
                        disablePointerEventsOnTree(this)
                        null
                    }
                    onerror = { _, _, _, _, _ ->
                        isLoaded = true
                        hasError = true
                        currentOnError()
                        disablePointerEventsOnTree(this)
                        null
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio),
            update = { element ->
                val img = element as HTMLImageElement
                if (img.src != getImageUrl(url)) {
                    img.src = getImageUrl(url)
                }
                // Re-apply on every update in case Compose recreated the wrapper.
                disablePointerEventsOnTree(img)
            }
        )

        if (!isLoaded) {
            CircularProgressIndicator(
                modifier = Modifier.size(28.dp),
                color = NostrordColors.TextMuted,
                strokeWidth = 2.5.dp
            )
        }
    }
}

private fun ContentScale.toCssObjectFit(): String = when (this) {
    ContentScale.Crop -> "cover"
    ContentScale.FillBounds -> "fill"
    ContentScale.FillWidth -> "cover"
    ContentScale.FillHeight -> "cover"
    ContentScale.Inside -> "scale-down"
    else -> "contain"
}
