package org.nostr.nostrord.web.components

import kotlinx.browser.document
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
}

/**
 * A chat inline video with retry-on-failure. Without this wrapping, a network blip during the
 * preload="metadata" fetch leaves the bare <video> as a dead black box for the rest of the
 * page lifetime — the user has to reload the whole site. The host's flaky Content-Type or
 * Range responses produce the same dead state.
 *
 * On the media element's error event we flip to a fallback panel; the retry button bumps a
 * remount key so React re-creates the element from scratch (HTMLMediaElement has no "reset
 * everything" call that reliably re-attempts MEDIA_ERR_SRC_NOT_SUPPORTED, so a real remount
 * is the safe path).
 */
val ChatVideo =
    FC<ChatVideoProps> { props ->
        val (failed, setFailed) = useState { false }
        // Bumped on retry: React key on the <video> tag, forcing a full re-mount so the
        // browser re-fetches and re-decodes from zero instead of holding the broken state.
        val (attempt, setAttempt) = useState { 0 }
        val videoRef = useRef<HTMLVideoElement>(null)

        // Release the underlying media resource on unmount. Without this, switching
        // between groups / relays piles up <video> elements that React has dropped
        // from the tree but the browser still holds open (decoder + network buffer +
        // connection). Browsers cap simultaneous media elements (~75 Chrome / fewer
        // Firefox); once the cap is hit, freshly-mounted videos render as a black
        // box that "comes back after a while" — exactly when the engine GC's the
        // stale resources. pause() + removeAttribute("src") + load() is the
        // canonical incantation to release them eagerly.
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
                        setAttempt { it + 1 }
                    }
                    +"Retry"
                }
            }
        } else {
            video {
                key = "${props.videoUrl}#$attempt"
                ref = videoRef
                className = ClassName("msg-video")
                src = props.videoUrl
                controls = true
                // Show a preview frame without downloading/playing the whole file (no autoplay).
                preload = "metadata"
                playsInline = true
                // Notify the feed when the video's intrinsic dimensions arrive
                // (preload="metadata" fires loadedmetadata, not load) so the
                // scroll can re-pin to bottom for any user still anchored
                // there. Same mechanic as ChatImage's onLoad. (issue #74)
                onLoadedMetadata = {
                    document.asDynamic().dispatchEvent(
                        js("new CustomEvent('chat-content-loaded')"),
                    )
                    Unit
                }
                onError = { setFailed(true) }
            }
        }
    }
