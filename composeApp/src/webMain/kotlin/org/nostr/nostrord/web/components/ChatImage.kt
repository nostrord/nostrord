package org.nostr.nostrord.web.components

import kotlinx.browser.document
import react.FC
import react.Props
import react.dom.html.ReactHTML.img
import react.useState
import web.cssom.ClassName

external interface ChatImageProps : Props {
    var imageUrl: String
}

/**
 * A chat inline image. Opens fullscreen on click. For images that have transparency it samples
 * the pixels and adds a white or dark backdrop so a dark or light transparent logo stays visible
 * on the chat surface.
 *
 * The sampling reads the visible <img> itself (loaded with crossOrigin="anonymous") rather than
 * downloading a second cache-busted copy, so the backdrop class is ready the moment the image
 * paints instead of after a full extra round-trip. Most Nostr media hosts (nostr.build, Blossom,
 * etc.) send `Access-Control-Allow-Origin: *`, so the CORS load succeeds and the canvas is
 * readable. If a host sends no CORS headers the crossOrigin load errors; we then reload the same
 * element WITHOUT crossOrigin so it still displays (just with no backdrop, same as before).
 */
val ChatImage =
    FC<ChatImageProps> { props ->
        val (backdrop, setBackdrop) = useState<String?> { null }
        // Flips to true if the crossOrigin load fails (host without CORS headers); the retry
        // render drops crossOrigin so the image still shows. We then skip sampling (the plain
        // image would taint the canvas anyway).
        val (corsBlocked, setCorsBlocked) = useState { false }

        img {
            className = ClassName("msg-image" + (backdrop?.let { " $it" } ?: ""))
            // Sample-readable load. data: URIs are same-origin so crossOrigin is a no-op there.
            if (!corsBlocked) asDynamic().crossOrigin = "anonymous"
            src = props.imageUrl
            alt = ""
            onClick = { ImageViewer.show(props.imageUrl, backdrop) }
            onLoad = { event ->
                if (!corsBlocked) setBackdrop(analyzeBackdrop(event.currentTarget))
                // Dispatch when the image resolves dimensions so the chat feed can
                // re-pin to bottom if the user was already there — otherwise media
                // arriving after the initial scroll-to-bottom pushes the user
                // mid-feed. (issue #74)
                document.asDynamic().dispatchEvent(
                    js("new CustomEvent('chat-content-loaded')"),
                )
                Unit
            }
            onError = {
                // First failure is most likely the CORS preflight on a no-CORS host; retry
                // without crossOrigin so the image still displays.
                if (!corsBlocked) setCorsBlocked(true)
                Unit
            }
        }
    }

/**
 * Sample [image] into a small canvas. Returns "on-light" when the image has transparent areas
 * and dark content (needs a white backdrop), "on-dark" for light content (needs a dark backdrop),
 * or null when the image is effectively opaque or the canvas can't be read (cross-origin taint).
 */
private fun analyzeBackdrop(image: dynamic): String? {
    val size = 24
    return try {
        val canvas = document.createElement("canvas").asDynamic()
        canvas.width = size
        canvas.height = size
        val ctx = canvas.getContext("2d") ?: return null
        ctx.drawImage(image, 0, 0, size, size)
        val data = ctx.getImageData(0, 0, size, size).data
        val total = size * size
        var transparent = 0
        var opaque = 0
        var lumaSum = 0.0
        for (i in 0 until total) {
            val o = i * 4
            val alpha = data[o + 3].unsafeCast<Int>()
            if (alpha < 240) transparent++
            if (alpha > 200) {
                val r = data[o].unsafeCast<Int>()
                val g = data[o + 1].unsafeCast<Int>()
                val b = data[o + 2].unsafeCast<Int>()
                lumaSum += 0.299 * r + 0.587 * g + 0.114 * b
                opaque++
            }
        }
        // Mostly opaque → leave the image as-is.
        if (transparent < total * 0.05 || opaque == 0) {
            null
        } else if (lumaSum / opaque < 128.0) {
            "on-light"
        } else {
            "on-dark"
        }
    } catch (e: Throwable) {
        null
    }
}
