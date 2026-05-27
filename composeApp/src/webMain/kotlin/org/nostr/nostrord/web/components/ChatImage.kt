package org.nostr.nostrord.web.components

import kotlinx.browser.document
import react.FC
import react.Props
import react.dom.html.ReactHTML.img
import react.useEffectOnce
import react.useState
import web.cssom.ClassName

external interface ChatImageProps : Props {
    var imageUrl: String
}

/**
 * A chat inline image. Opens fullscreen on click. For images that have transparency it samples
 * the pixels (via a separate CORS-loaded copy, so the visible image never breaks) and adds a
 * white or dark backdrop so a dark or light transparent logo stays visible on the chat surface.
 * If the pixels can't be read (host sends no CORS headers) no backdrop is added.
 */
val ChatImage =
    FC<ChatImageProps> { props ->
        val (backdrop, setBackdrop) = useState<String?> { null }

        useEffectOnce {
            val probe = js("new Image()")
            probe.crossOrigin = "anonymous"
            probe.onload = {
                setBackdrop(analyzeBackdrop(probe))
                Unit
            }
            probe.onerror = { Unit }
            // Cache-bust remote URLs so this CORS fetch doesn't reuse the visible <img>'s
            // non-CORS cache entry (which would taint the canvas and block getImageData).
            // data: URIs are same-origin and self-contained — use them verbatim (a query
            // suffix would corrupt the base64).
            probe.src =
                if (props.imageUrl.startsWith("data:")) {
                    props.imageUrl
                } else {
                    props.imageUrl + (if (props.imageUrl.contains("?")) "&" else "?") + "cors=1"
                }
        }

        img {
            className = ClassName("msg-image" + (backdrop?.let { " $it" } ?: ""))
            src = props.imageUrl
            alt = ""
            onClick = { ImageViewer.show(props.imageUrl, backdrop) }
            // Dispatch when the image resolves dimensions so the chat feed can
            // re-pin to bottom if the user was already there — otherwise media
            // arriving after the initial scroll-to-bottom pushes the user
            // mid-feed. (issue #74)
            onLoad = {
                document.asDynamic().dispatchEvent(
                    js("new CustomEvent('chat-content-loaded')"),
                )
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
