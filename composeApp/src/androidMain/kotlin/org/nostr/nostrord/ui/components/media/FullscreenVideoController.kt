package org.nostr.nostrord.ui.components.media

import android.app.Activity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * State carried into the fullscreen overlay. [startPositionMs] lets the overlay resume from
 * wherever the inline player was, so toggling fullscreen feels seamless.
 */
data class FullscreenVideoRequest(
    val url: String,
    val startPositionMs: Long,
)

/**
 * Hoists the fullscreen video state out of the chat composable tree. Without this, the
 * fullscreen Dialog was a child of [PlatformVideoPlayer], so any recomposition that
 * unmounted the inline player (scroll, navigation, layout breakpoint switch) destroyed
 * the dialog too — fullscreen would snap shut mid-watch.
 */
class FullscreenVideoController {
    var active: FullscreenVideoRequest? by mutableStateOf(null)
        private set

    private var onCloseListener: ((Long) -> Unit)? = null

    fun open(
        url: String,
        startPositionMs: Long,
        onClose: (Long) -> Unit,
    ) {
        active = FullscreenVideoRequest(url, startPositionMs)
        onCloseListener = onClose
    }

    fun close(currentPositionMs: Long) {
        val cb = onCloseListener
        onCloseListener = null
        active = null
        cb?.invoke(currentPositionMs)
    }
}

val LocalFullscreenVideoController = compositionLocalOf<FullscreenVideoController> {
    error(
        "FullscreenVideoController not provided. Wrap content with " +
            "CompositionLocalProvider(LocalFullscreenVideoController provides controller).",
    )
}

@Composable
fun FullscreenVideoOverlay(controller: FullscreenVideoController) {
    val request = controller.active ?: return
    FullscreenVideoOverlayContent(
        url = request.url,
        startPositionMs = request.startPositionMs,
        onClose = { positionMs -> controller.close(positionMs) },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun FullscreenVideoOverlayContent(
    url: String,
    startPositionMs: Long,
    onClose: (positionMs: Long) -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

    val exoPlayer =
        remember(url) {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                seekTo(startPositionMs)
                playWhenReady = true
            }
        }

    DisposableEffect(url) {
        onDispose { exoPlayer.release() }
    }

    // Hide system bars while fullscreen is active; restore on dismiss.
    DisposableEffect(activity) {
        val window = activity?.window
        val insetsController =
            window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        insetsController?.apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    BackHandler { onClose(exoPlayer.currentPosition) }

    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    setBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    layoutParams =
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT,
                        )
                    setShowPreviousButton(false)
                    setShowNextButton(false)
                    controllerShowTimeoutMs = 3000
                    controllerHideOnTouch = true
                    setFullscreenButtonState(true)
                    setFullscreenButtonClickListener { _ -> onClose(exoPlayer.currentPosition) }
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
