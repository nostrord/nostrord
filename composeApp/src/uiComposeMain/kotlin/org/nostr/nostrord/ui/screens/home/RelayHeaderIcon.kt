package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import kotlinx.coroutines.delay
import org.nostr.nostrord.nostr.isValidIconUrl
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.buildRelayIconRequest
import org.nostr.nostrord.ui.util.generateColorFromString
import org.nostr.nostrord.ui.util.relayFallbackPainter

/**
 * Square relay icon with a fallback letter/painter under a remote icon, retried with
 * exponential backoff. Used by the manage-relay header.
 */
@Composable
internal fun RelayHeaderIcon(
    relayUrl: String,
    iconUrl: String?,
    label: String,
    size: Dp,
) {
    val context = LocalPlatformContext.current
    val fallbackPainter = if (iconUrl.isNullOrBlank()) relayFallbackPainter(relayUrl) else null
    val hasIcon = isValidIconUrl(iconUrl)
    var imageLoaded by remember(iconUrl) { mutableStateOf(false) }
    var retryCount by remember(iconUrl) { mutableIntStateOf(0) }
    var loadError by remember(iconUrl) { mutableStateOf(false) }
    LaunchedEffect(loadError, retryCount) {
        if (loadError && !imageLoaded) {
            val backoffMs = minOf(3_000L * (1 shl minOf(retryCount, 7)), 5 * 60_000L)
            delay(backoffMs)
            retryCount++
            loadError = false
        }
    }

    Box(
        modifier =
        Modifier
            .size(size)
            .clip(RoundedCornerShape(14.dp))
            .background(if (imageLoaded && hasIcon) NostrordColors.BackgroundDark else generateColorFromString(relayUrl)),
        contentAlignment = Alignment.Center,
    ) {
        // Base layer: fallback shown until image overlays it
        if (fallbackPainter != null) {
            androidx.compose.foundation.Image(
                painter = fallbackPainter,
                contentDescription = label,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                contentScale = ContentScale.Crop,
            )
        } else if (!imageLoaded) {
            Text(
                text = label.take(1).uppercase(),
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
            )
        }
        if (hasIcon) {
            key(retryCount) {
                AsyncImage(
                    model = buildRelayIconRequest(iconUrl!!, context),
                    contentDescription = label,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                    contentScale = ContentScale.Crop,
                    onState = { state ->
                        when (state) {
                            is AsyncImagePainter.State.Success -> {
                                imageLoaded = true
                                loadError = false
                            }
                            is AsyncImagePainter.State.Error -> {
                                imageLoaded = false
                                loadError = true
                            }
                            else -> {}
                        }
                    },
                )
            }
        }
    }
}
