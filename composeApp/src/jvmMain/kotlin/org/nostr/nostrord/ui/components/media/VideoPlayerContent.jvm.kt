package org.nostr.nostrord.ui.components.media

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.kdroidfilter.composemediaplayer.InitialPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerState
import io.github.kdroidfilter.composemediaplayer.VideoPlayerSurface
import io.github.kdroidfilter.composemediaplayer.rememberVideoPlayerState
import kotlinx.coroutines.delay

private const val SLIDER_MAX = 1000f

@Composable
actual fun PlatformVideoPlayer(
    url: String,
    thumbnailUrl: String?,
    aspectRatio: Float,
    onFallbackClick: () -> Unit,
    modifier: Modifier
) {
    val playerState = rememberVideoPlayerState()
    var isFullscreen by remember { mutableStateOf(false) }
    var hasStarted by remember(url) { mutableStateOf(false) }

    // Extract thumbnail via ffmpeg before user clicks play
    var thumbnail by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        thumbnail = VideoThumbnailExtractor.extractFirstFrame(url)
    }

    LaunchedEffect(url) {
        playerState.openUri(url, InitialPlayerState.PAUSE)
    }

    VideoPlayerBox(
        playerState = playerState,
        thumbnail = thumbnail,
        hasStarted = hasStarted,
        aspectRatio = aspectRatio,
        isFullscreen = false,
        onPlay = { hasStarted = true; playerState.play() },
        onFullscreenClick = { isFullscreen = true },
        modifier = modifier.widthIn(max = 400.dp)
    )

    if (isFullscreen) {
        Dialog(
            onDismissRequest = { isFullscreen = false },
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                dismissOnBackPress = true,
                dismissOnClickOutside = false
            )
        ) {
            VideoPlayerBox(
                playerState = playerState,
                thumbnail = null,
                hasStarted = true,
                aspectRatio = aspectRatio,
                isFullscreen = true,
                onPlay = { playerState.play() },
                onFullscreenClick = { isFullscreen = false },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun VideoPlayerBox(
    playerState: VideoPlayerState,
    thumbnail: ImageBitmap?,
    hasStarted: Boolean,
    aspectRatio: Float,
    isFullscreen: Boolean,
    onPlay: () -> Unit,
    onFullscreenClick: () -> Unit,
    modifier: Modifier
) {
    val hoverInteraction = remember { MutableInteractionSource() }
    val isHovered by hoverInteraction.collectIsHoveredAsState()
    var showControls by remember { mutableStateOf(true) }

    LaunchedEffect(isHovered, playerState.isPlaying) {
        if (isHovered || !playerState.isPlaying) {
            showControls = true
        } else {
            delay(3000)
            showControls = false
        }
    }

    val sizeModifier = if (isFullscreen) Modifier
    else Modifier.aspectRatio(aspectRatio, matchHeightConstraintsFirst = false)

    Box(
        modifier = modifier
            .then(sizeModifier)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .hoverable(hoverInteraction)
            .clickable {
                if (!hasStarted) onPlay()
                else if (playerState.isPlaying) playerState.pause()
                else playerState.play()
            }
            .pointerHoverIcon(PointerIcon.Hand)
    ) {
        if (hasStarted) {
            VideoPlayerSurface(
                playerState = playerState,
                modifier = Modifier.fillMaxSize()
            )
        } else if (thumbnail != null) {
            Image(
                bitmap = thumbnail,
                contentDescription = "Video preview",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }

        AnimatedVisibility(
            visible = !playerState.isPlaying || !hasStarted,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.6f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }

        if (hasStarted) {
            AnimatedVisibility(
                visible = showControls && playerState.hasMedia,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.align(Alignment.BottomCenter)
            ) {
                VideoControls(
                    playerState = playerState,
                    isFullscreen = isFullscreen,
                    onFullscreenClick = onFullscreenClick
                )
            }
        }
    }
}

@Composable
private fun VideoControls(
    playerState: VideoPlayerState,
    isFullscreen: Boolean,
    onFullscreenClick: () -> Unit
) {
    var isMuted by remember { mutableStateOf(false) }
    var localSlider by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }

    val displayPos = if (isDragging) localSlider else playerState.sliderPos

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color.Transparent,
                        Color.Black.copy(alpha = 0.4f),
                        Color.Black.copy(alpha = 0.7f)
                    )
                )
            )
            .padding(horizontal = 8.dp)
            .padding(top = 16.dp, bottom = 4.dp)
    ) {
        Slider(
            value = displayPos,
            onValueChange = { pos ->
                isDragging = true
                localSlider = pos
                playerState.userDragging = true
                playerState.sliderPos = pos
            },
            onValueChangeFinished = {
                playerState.seekTo(localSlider)
                playerState.userDragging = false
                isDragging = false
            },
            valueRange = 0f..SLIDER_MAX,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .height(20.dp),
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color.White.copy(alpha = 0.3f)
            )
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .padding(top = 20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ControlButton(
                    icon = if (playerState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (playerState.isPlaying) "Pause" else "Play",
                    onClick = {
                        if (playerState.isPlaying) playerState.pause()
                        else playerState.play()
                    }
                )

                Spacer(Modifier.width(4.dp))

                ControlButton(
                    icon = if (isMuted) Icons.AutoMirrored.Filled.VolumeOff
                           else Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = if (isMuted) "Unmute" else "Mute",
                    onClick = {
                        isMuted = !isMuted
                        playerState.volume = if (isMuted) 0f else 1f
                    }
                )

                Spacer(Modifier.width(8.dp))

                Text(
                    text = "${playerState.positionText} / ${playerState.durationText}",
                    color = Color.White,
                    fontSize = 11.sp
                )
            }

            ControlButton(
                icon = if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                contentDescription = if (isFullscreen) "Exit fullscreen" else "Fullscreen",
                onClick = onFullscreenClick
            )
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(18.dp)
        )
    }
}
