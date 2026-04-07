@file:OptIn(ExperimentalComposeUiApi::class)

package org.nostr.nostrord.ui.components.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.WebElementView
import kotlinx.browser.document
import org.nostr.nostrord.ui.components.chat.LocalAnimatedImageHidden
import org.nostr.nostrord.ui.theme.NostrordColors
import org.w3c.dom.HTMLVideoElement

private const val VIDEO_BORDER_RADIUS_PX = 8

/**
 * WasmJS: Inline HTML5 `<video>` element via WebElementView.
 */
@Composable
actual fun PlatformVideoPlayer(
    url: String,
    thumbnailUrl: String?,
    aspectRatio: Float,
    onFallbackClick: () -> Unit,
    modifier: Modifier
) {
    val isHidden = LocalAnimatedImageHidden.current

    Box(
        modifier = modifier
            .widthIn(max = 400.dp)
            .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(VIDEO_BORDER_RADIUS_PX.dp))
            .background(NostrordColors.SurfaceVariant)
    ) {
        WebElementView(
            factory = {
                (document.createElement("video") as HTMLVideoElement).apply {
                    src = url
                    controls = true
                    preload = "metadata"
                    if (thumbnailUrl != null) poster = thumbnailUrl
                    style.width = "100%"
                    style.height = "100%"
                    style.borderRadius = "${VIDEO_BORDER_RADIUS_PX}px"
                    style.setProperty("object-fit", "contain")
                    style.setProperty("background-color", "#000")
                    style.display = "block"
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false),
            update = { element ->
                val video = element as HTMLVideoElement
                if (video.src != url) video.src = url
                if (thumbnailUrl != null) video.poster = thumbnailUrl
                // Hide via CSS so video keeps playing but doesn't overlap canvas modals
                video.style.visibility = if (isHidden) "hidden" else "visible"
                (video.parentElement as? org.w3c.dom.HTMLElement)?.style?.visibility =
                    if (isHidden) "hidden" else "visible"
            }
        )
    }
}
