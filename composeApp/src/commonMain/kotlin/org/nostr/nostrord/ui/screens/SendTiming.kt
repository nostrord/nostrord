package org.nostr.nostrord.ui.screens

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Minimum on-screen lifetime for a composer's send spinner. Optimistic group/thread sends
 * resolve a few ms after the local sign step, so without a floor the spinner flips on and off
 * too fast to ever be seen. Keeps the "sending" feedback perceptible and consistent with the
 * slower NIP-17 DM send across the chat, threads, and DM composers (web and native).
 */
val MIN_SEND_SPINNER: Duration = 400.milliseconds

/** Runs [block], then waits so the total elapsed time is at least [minDuration]. */
suspend fun <T> withMinDuration(minDuration: Duration = MIN_SEND_SPINNER, block: suspend () -> T): T {
    val start = TimeSource.Monotonic.markNow()
    val result = block()
    val remaining = minDuration - start.elapsedNow()
    if (remaining.isPositive()) delay(remaining)
    return result
}
