package org.nostr.nostrord.web.components

import kotlinx.browser.document
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.iframe
import react.dom.html.ReactHTML.img
import react.useState
import web.cssom.ClassName

external interface YouTubeEmbedProps : Props {
    /** The 11-character YouTube video id. */
    var videoId: String
}

// Hosts the embed iframe touches; preconnecting on hover gets DNS + TLS out of
// the way before the click so the player bootstraps noticeably faster.
private val WARM_HOSTS =
    listOf(
        "https://www.youtube-nocookie.com",
        "https://www.google.com",
        "https://googleads.g.doubleclick.net",
        "https://static.doubleclick.net",
        "https://i.ytimg.com",
    )

private var warmed = false

private fun warmConnections() {
    if (warmed) return
    warmed = true
    WARM_HOSTS.forEach { host ->
        val link = document.createElement("link")
        link.setAttribute("rel", "preconnect")
        link.setAttribute("href", host)
        document.head?.appendChild(link)
    }
}

/**
 * Inline YouTube preview that mirrors the native YouTubeLinkCard: a 16:9
 * thumbnail with a play overlay. On the web we can do one better than native
 * (which opens the browser) and play inline, so clicking swaps the thumbnail
 * for the privacy-friendly youtube-nocookie embed and autoplays.
 *
 * Nothing is requested from YouTube until the user presses play; before that
 * it is just an <img> thumbnail fetch, matching the cost model of ChatVideo.
 */
val YouTubeEmbed =
    FC<YouTubeEmbedProps> { props ->
        val (playing, setPlaying) = useState { false }
        val thumbnailUrl = "https://i.ytimg.com/vi/${props.videoId}/hqdefault.jpg"
        // Settings → Media: when auto-load is off we show a "Tap to load" placeholder
        // and fetch nothing (not even the thumbnail) until the user reveals it.
        val autoLoad = useAutoLoadMedia()
        val (revealed, setRevealed) = useState { false }
        if (!autoLoad && !revealed) {
            mediaGatePlaceholder("video") { setRevealed(true) }
            return@FC
        }

        div {
            className = ClassName("msg-youtube")
            if (playing) {
                iframe {
                    className = ClassName("msg-youtube-frame")
                    src =
                        "https://www.youtube-nocookie.com/embed/${props.videoId}" +
                        "?autoplay=1&rel=0&playsinline=1"
                    title = "YouTube video"
                    // "fullscreen" must be in the permissions policy or the
                    // player's fullscreen button silently does nothing.
                    asDynamic().allow =
                        "accelerometer; autoplay; clipboard-write; " +
                        "encrypted-media; gyroscope; picture-in-picture; fullscreen"
                    // React expects the camel-cased boolean prop (allowFullScreen);
                    // lowercase variants are dropped and fullscreen stays disabled.
                    asDynamic().allowFullScreen = true
                    asDynamic().frameBorder = "0"
                }
            } else {
                img {
                    className = ClassName("msg-youtube-thumb")
                    src = thumbnailUrl
                    alt = "YouTube video thumbnail"
                }
                div {
                    className = ClassName("msg-youtube-play")
                    onMouseEnter = { warmConnections() }
                    onClick = { setPlaying(true) }
                }
            }
        }
    }
