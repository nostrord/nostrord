package org.nostr.nostrord.web.components

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.awaitCancellation
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.video
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLVideoElement

external interface ChatVideoProps : Props {
    var videoUrl: String

    /** Optional NIP-92 imeta thumbnail, shown as the poster so a not-yet-played
     *  video previews a frame instead of a black box. */
    var posterUrl: String?
}

// How long the load may stall after the user presses play before we abort and
// retry. nostr.build routinely accepts the socket and sends nothing (readyState
// 0 / networkState LOADING, no `loadedmetadata`, no `error`); a fresh request
// usually succeeds, so keep this short. Reset by any real progress.
private const val WATCHDOG_MS = 5000

// Silent watchdog re-mounts (fresh connection) before showing the Retry panel.
private const val MAX_AUTO_RETRIES = 4

/**
 * A chat inline video that loads ONLY when the user presses play (preload="none").
 *
 * Eagerly fetching every video's metadata on mount opened a burst of Range
 * requests to the media host; returning to a group fired dozens at once, and
 * nostr.build would accept the sockets and then stall at byte 0 (no metadata, no
 * error) — leaving dead 0:00 players that only a full reload fixed. Remounting
 * the element didn't help because the browser reuses the same stalled HTTP/2
 * connection; only a genuinely fresh request (a manual retry seconds later, or a
 * reload) recovered it.
 *
 * Click-to-load removes the trigger: nothing is requested until the user asks
 * for a specific video, one at a time, on a fresh connection. A stall on that
 * single load is still covered by the watchdog (abort + silent retry, then a
 * Retry panel) so it never hangs forever.
 */
val ChatVideo =
    FC<ChatVideoProps> { props ->
        // User pressed play: only now do we fetch + play.
        val (started, setStarted) = useState { false }
        val (failed, setFailed) = useState { false }
        // Bumped on retry (watchdog or manual): React key on the <video>, forcing a
        // full re-mount so the browser re-fetches from a fresh element.
        val (attempt, setAttempt) = useState { 0 }
        // Flips true once metadata arrives; ends the watchdog and swaps the spinner
        // for the native controls.
        val (metaLoaded, setMetaLoaded) = useState { false }
        val videoRef = useRef<HTMLVideoElement>(null)

        // Release the underlying media resource on unmount so switching groups /
        // relays doesn't pile up <video> elements the browser still holds open
        // (decoder + buffer + connection); the media-element cap otherwise makes
        // fresh videos render black until the engine GCs the stale ones.
        useEffect(props.videoUrl) {
            try {
                awaitCancellation()
            } finally {
                val node = videoRef.current ?: return@useEffect
                runCatching {
                    node.asDynamic().pause()
                    node.removeAttribute("src")
                    node.asDynamic().load()
                }
            }
        }

        // Kick off playback once the user has started (and again after a retry
        // re-mount). Wrapped because play() rejects with AbortError when the load
        // is torn down mid-flight, which is expected and harmless.
        useEffect(started, attempt) {
            if (!started) return@useEffect
            val node = videoRef.current ?: return@useEffect
            runCatching {
                val p = node.asDynamic().play()
                if (p != null) p.then(null, { _: dynamic -> null })
            }
        }

        // Stall watchdog: only while a started load hasn't reached metadata yet.
        // Any real progress resets it; on fire, abort and recover (silent retry,
        // then the Retry panel).
        useEffect(started, metaLoaded, failed, attempt) {
            val node = videoRef.current
            if (node == null || !started || metaLoaded || failed) return@useEffect
            var timer = 0
            val fire: () -> Unit = {
                val willRetry = attempt < MAX_AUTO_RETRIES
                runCatching {
                    node.asDynamic().pause()
                    node.removeAttribute("src")
                    node.asDynamic().load()
                }
                if (willRetry) setAttempt { it + 1 } else setFailed(true)
            }
            val arm: () -> Unit = {
                if (timer != 0) window.clearTimeout(timer)
                timer = window.setTimeout(fire, WATCHDOG_MS)
            }
            val onProgress: (dynamic) -> Unit = { arm() }
            node.asDynamic().addEventListener("progress", onProgress)
            node.asDynamic().addEventListener("loadeddata", onProgress)
            arm()
            try {
                awaitCancellation()
            } finally {
                if (timer != 0) window.clearTimeout(timer)
                node.asDynamic().removeEventListener("progress", onProgress)
                node.asDynamic().removeEventListener("loadeddata", onProgress)
            }
        }

        if (failed) {
            div {
                className = ClassName("msg-video-error")
                div {
                    className = ClassName("msg-video-error-label")
                    +"Video failed to load."
                }
                button {
                    className = ClassName("msg-video-retry")
                    onClick = {
                        setFailed(false)
                        setMetaLoaded(false)
                        setStarted(true)
                        setAttempt { it + 1 }
                    }
                    +"Retry"
                }
            }
        } else {
            div {
                className = ClassName("msg-video-wrap")
                video {
                    key = "${props.videoUrl}#$attempt"
                    ref = videoRef
                    className = ClassName("msg-video")
                    src = props.videoUrl
                    // Static thumbnail (NIP-92 imeta) shown until playback — a cheap
                    // <img>-style fetch, NOT a video range request, so it never hits
                    // the stall. Absent on many messages; then it's the black box.
                    props.posterUrl?.let { poster = it }
                    // No network until the user presses play — this is the whole
                    // point: it kills the load-everything-on-mount burst.
                    preload = "none"
                    // Controls (and the 0:00 bar) only after the user starts; before
                    // that our own play overlay is shown.
                    controls = started
                    playsInline = true
                    onLoadedMetadata = {
                        setMetaLoaded(true)
                        // Re-pin the scroll for anyone anchored at the bottom now
                        // that the video's height is known (issue #74).
                        document.asDynamic().dispatchEvent(
                            js("new CustomEvent('chat-content-loaded')"),
                        )
                        Unit
                    }
                    onError = { setFailed(true) }
                }
                if (!started) {
                    // Play overlay: the only thing that triggers a fetch. Once
                    // started, the native controls show their own buffering spinner,
                    // so we don't add a second overlay on top of it.
                    div {
                        className = ClassName("msg-video-play")
                        onClick = {
                            setStarted(true)
                            // Start within the user gesture so autoplay policies allow it.
                            videoRef.current?.let { node ->
                                runCatching {
                                    val p = node.asDynamic().play()
                                    if (p != null) p.then(null, { _: dynamic -> null })
                                }
                            }
                        }
                    }
                }
            }
        }
    }
