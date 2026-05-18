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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Hoists the fullscreen video state out of the chat composable tree. Without this, the
 * fullscreen Dialog was a child of [PlatformVideoPlayer], so any recomposition that
 * unmounted the inline player (scroll, navigation, layout breakpoint switch) destroyed
 * the dialog too — fullscreen would snap shut mid-watch.
 *
 * Ownership model: inline borrows the player to the overlay. The inline composable
 * normally owns release; while fullscreen is active, inline's onDispose defers release
 * to the overlay, which calls [onCloseListener] on dismiss with whether inline is still
 * around to take the player back.
 */
class FullscreenVideoController {
    var active: ExoPlayer? by mutableStateOf(null)
        private set

    private var onCloseListener: (() -> Unit)? = null

    fun open(
        player: ExoPlayer,
        onClose: () -> Unit,
    ) {
        active = player
        onCloseListener = onClose
    }

    fun close() {
        val cb = onCloseListener
        onCloseListener = null
        active = null
        cb?.invoke()
    }
}

/** Null when unprovided — callers fall back to no-op so @Preview and other entrypoints don't crash. */
val LocalFullscreenVideoController = staticCompositionLocalOf<FullscreenVideoController?> { null }

@Composable
fun FullscreenVideoOverlay(controller: FullscreenVideoController) {
    val player = controller.active ?: return
    FullscreenVideoOverlayContent(
        exoPlayer = player,
        onClose = { controller.close() },
    )
}

@OptIn(UnstableApi::class)
@Composable
private fun FullscreenVideoOverlayContent(
    exoPlayer: ExoPlayer,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context as? Activity

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

    BackHandler { onClose() }

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
                    setFullscreenButtonClickListener { _ -> onClose() }
                }
            },
            update = { it.player = exoPlayer },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
