package org.nostr.nostrord.ui.components.media

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@OptIn(UnstableApi::class)
@Composable
actual fun PlatformVideoPlayer(
    url: String,
    thumbnailUrl: String?,
    aspectRatio: Float,
    onFallbackClick: () -> Unit,
    modifier: Modifier,
) {
    val context = LocalContext.current
    val fullscreenController = LocalFullscreenVideoController.current

    val exoPlayer =
        remember(url) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = false
            }
        }

    // The inline player can be unmounted while fullscreen is still active (chat scrolled
    // away, navigated to another group). Use a guard so the fullscreen-close callback
    // doesn't seek on a released player.
    var inlineAlive by remember { mutableStateOf(true) }

    DisposableEffect(url) {
        onDispose {
            inlineAlive = false
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                setBackgroundColor(android.graphics.Color.BLACK)
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShowPreviousButton(false)
                setShowNextButton(false)
                setShowFastForwardButton(false)
                setShowRewindButton(false)
                setShowSubtitleButton(false)
                controllerShowTimeoutMs = 3000
                controllerHideOnTouch = true
                setFullscreenButtonState(false)
                setFullscreenButtonClickListener { _ ->
                    val position = exoPlayer.currentPosition
                    exoPlayer.pause()
                    fullscreenController.open(
                        url = url,
                        startPositionMs = position,
                        onClose = { newPosition ->
                            if (inlineAlive) {
                                try {
                                    exoPlayer.seekTo(newPosition)
                                } catch (_: Exception) {
                                    // Released between the guard check and seekTo — ignore.
                                }
                            }
                        },
                    )
                }
            }
        },
        update = { it.player = exoPlayer },
        modifier =
        modifier
            .widthIn(max = 400.dp)
            .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    )
}
