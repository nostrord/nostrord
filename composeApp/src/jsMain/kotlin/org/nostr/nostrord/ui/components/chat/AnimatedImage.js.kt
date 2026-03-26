@file:OptIn(ExperimentalComposeUiApi::class)

package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.WebElementView
import kotlinx.browser.document
import org.nostr.nostrord.utils.getImageUrl
import org.w3c.dom.HTMLImageElement

private const val IMAGE_BORDER_RADIUS_DP = 8f

@Composable
actual fun AnimatedImage(
    url: String,
    modifier: Modifier,
    contentScale: ContentScale,
    onClick: () -> Unit
) {
    var aspectRatio by remember(url) { mutableStateOf(4f / 3f) }
    val currentOnClick by rememberUpdatedState(onClick)

    if (LocalAnimatedImageHidden.current) {
        Box(modifier = modifier.fillMaxWidth().aspectRatio(aspectRatio))
        return
    }

    WebElementView(
        factory = {
            (document.createElement("img") as HTMLImageElement).apply {
                src = getImageUrl(url)
                style.width = "100%"
                style.height = "100%"
                style.objectFit = contentScale.toCssObjectFit()
                style.borderRadius = "${IMAGE_BORDER_RADIUS_DP}px"
                style.cursor = "pointer"
                onload = {
                    if (naturalWidth > 0 && naturalHeight > 0) {
                        aspectRatio = naturalWidth.toFloat() / naturalHeight.toFloat()
                    }
                    null
                }
                this.onclick = { currentOnClick() }
            }
        },
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(aspectRatio),
        update = { element ->
            val img = element as HTMLImageElement
            if (img.src != getImageUrl(url)) {
                img.src = getImageUrl(url)
            }
            img.onclick = { currentOnClick() }
        }
    )
}

private fun ContentScale.toCssObjectFit(): String = when (this) {
    ContentScale.Crop -> "cover"
    ContentScale.FillBounds -> "fill"
    ContentScale.FillWidth -> "cover"
    ContentScale.FillHeight -> "cover"
    ContentScale.Inside -> "scale-down"
    else -> "contain"
}
