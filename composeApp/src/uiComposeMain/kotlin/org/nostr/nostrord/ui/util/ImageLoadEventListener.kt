package org.nostr.nostrord.ui.util

import coil3.EventListener
import coil3.request.ErrorResult
import coil3.request.ImageRequest

/**
 * Coil listener that logs image load failures (mostly avatars and chat photos) so a regression in
 * loading is observable from logs instead of only from the visible placeholder. Attached to the
 * native ImageLoaders (Android / iOS / desktop).
 *
 * Error-only by design: it stays silent during normal operation and only emits on an actual failure
 * (429 / 5xx / timeout / decode error), so it is cheap to leave on in every build and surfaces the
 * failing host and cause when avatars start falling back to placeholders.
 */
object ImageLoadEventListener : EventListener() {
    override fun onError(
        request: ImageRequest,
        result: ErrorResult,
    ) {
        val cause = result.throwable.message ?: result.throwable.toString()
        println("NOSTRORD_IMG load failed data=${request.data} error=$cause")
    }
}
