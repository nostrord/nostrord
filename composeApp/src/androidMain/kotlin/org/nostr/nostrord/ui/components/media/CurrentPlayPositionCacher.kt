package org.nostr.nostrord.ui.components.media

import androidx.media3.common.Player
import kotlin.math.abs

/**
 * URL is passed at construction (not derived from `onMediaItemTransition`) because we
 * create one player per composable, so the URL is fixed for the listener's lifetime.
 * Relying on the transition callback would lose the URL when notifications race with
 * `release()`, leaving the dispose-time save with no key to persist under.
 */
class CurrentPlayPositionCacher(
    private val player: Player,
    private val url: String,
) : Player.Listener {
    override fun onIsPlayingChanged(isPlaying: Boolean) {
        save()
    }

    override fun onPlaybackStateChanged(playbackState: Int) {
        when (playbackState) {
            Player.STATE_READY -> {
                VideoViewedPositionCache.get(url)?.let { saved ->
                    if (abs(player.currentPosition - saved) > VideoViewedPositionCache.RESUME_THRESHOLD_MS) {
                        player.seekTo(saved)
                    }
                }
            }
            else -> save()
        }
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (player.playbackState != Player.STATE_IDLE) {
            VideoViewedPositionCache.put(url, newPosition.positionMs)
        }
    }

    private fun save() {
        if (abs(player.currentPosition) > 1) {
            VideoViewedPositionCache.put(url, player.currentPosition)
        }
    }
}
