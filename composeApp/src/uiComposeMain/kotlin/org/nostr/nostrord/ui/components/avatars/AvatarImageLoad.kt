package org.nostr.nostrord.ui.components.avatars

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import coil3.compose.AsyncImagePainter
import kotlinx.coroutines.delay

/** Self-healing avatar load: at most this many retries per URL before giving up to the placeholder. */
private const val MAX_AVATAR_RETRIES = 3

/** Holder returned by [rememberAvatarImageState]. */
class AvatarImageLoad internal constructor(
    /** Current Coil load state; drive the placeholder + the `!is Error` gate from this. */
    val state: AsyncImagePainter.State,
    /** Pass to the AsyncImage's `onState`. */
    val onState: (AsyncImagePainter.State) -> Unit,
)

/**
 * Drives an avatar's Coil load state with bounded retry-with-backoff, keyed on [url] (a metadata URL
 * change resets it). When Coil reports Error, it waits a short exponential backoff and resets the
 * state to Empty (up to [MAX_AVATAR_RETRIES] times), which re-composes the Error-gated AsyncImage and
 * issues a fresh load. A transient failure (429 / 5xx / timeout, or a refetch after a disk-cache
 * eviction) therefore self-heals instead of latching the avatar to its placeholder for the rest of
 * the session — the cause of avatars degrading to placeholders the longer the app stayed open
 * (each avatar only needed one transient failure to stick, and the set never recovered until a
 * process restart).
 *
 * REQUIREMENT: the call site MUST gate its AsyncImage out on Error (`state !is Error`). The retry
 * works by resetting Error -> Empty so the gate re-composes a fresh AsyncImage; an always-composed
 * AsyncImage would keep its Error painter and never reload.
 */
@Composable
fun rememberAvatarImageState(url: String?): AvatarImageLoad {
    var state by remember(url) { mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty) }
    var attempts by remember(url) { mutableStateOf(0) }
    LaunchedEffect(state, url) {
        if (!url.isNullOrBlank() && state is AsyncImagePainter.State.Error && attempts < MAX_AVATAR_RETRIES) {
            delay(2_000L shl attempts) // 2s, 4s, 8s
            attempts++
            state = AsyncImagePainter.State.Empty
        }
    }
    return AvatarImageLoad(state) { state = it }
}
