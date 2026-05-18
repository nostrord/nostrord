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
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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
            ExoPlayer
                .Builder(context)
                .setMediaSourceFactory(DefaultMediaSourceFactory(VideoCache.dataSourceFactory()))
                .setLoadControl(feedTunedLoadControl())
                .build()
                .apply {
                    addListener(CurrentPlayPositionCacher(this, url))
                    setMediaItem(MediaItem.fromUri(url))
                    prepare()
                    playWhenReady = false
                }
        }

    // Inline composable can be unmounted (scroll, navigation) while the overlay still
    // borrows our player. When that happens we defer release to the overlay's onClose.
    var inlineAlive by remember { mutableStateOf(true) }

    DisposableEffect(url) {
        onDispose {
            inlineAlive = false
            // Belt-and-suspenders: listener notifications during ExoPlayer.release() can
            // be lost to dispatch races, so persist the final position synchronously.
            if (exoPlayer.currentPosition > 1_000 && fullscreenController?.active != exoPlayer) {
                VideoViewedPositionCache.put(url, exoPlayer.currentPosition)
            }
            if (fullscreenController?.active != exoPlayer) {
                exoPlayer.release()
            }
        }
    }

    val isBorrowedByFullscreen = fullscreenController?.active == exoPlayer

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
                    fullscreenController?.open(
                        player = exoPlayer,
                        onClose = {
                            if (!inlineAlive) {
                                if (exoPlayer.currentPosition > 1_000) {
                                    VideoViewedPositionCache.put(url, exoPlayer.currentPosition)
                                }
                                exoPlayer.release()
                            }
                        },
                    )
                }
            }
        },
        update = { playerView ->
            // While fullscreen has the player, detach from the inline view so the same
            // ExoPlayer instance is only attached to one PlayerView at a time. When the
            // overlay closes, re-attach.
            playerView.player = if (isBorrowedByFullscreen) null else exoPlayer
        },
        modifier =
        modifier
            .widthIn(max = 400.dp)
            .aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black),
    )
}

/**
 * Default ExoPlayer buffers ~50s. With many videos visible in a chat, each one would
 * compete for memory and chew ~30MB per HD player. Cap at ~15s with fast playback
 * kick-in (~750ms). Pattern from Amethyst's `ExoPlayerBuilder.feedTunedLoadControl`.
 */
@OptIn(UnstableApi::class)
private fun feedTunedLoadControl(): DefaultLoadControl = DefaultLoadControl
    .Builder()
    .setBufferDurationsMs(
        10_000, // minBufferMs
        15_000, // maxBufferMs
        750, // bufferForPlaybackMs
        2_000, // bufferForPlaybackAfterRebufferMs
    ).setPrioritizeTimeOverSizeThresholds(true)
    .build()
