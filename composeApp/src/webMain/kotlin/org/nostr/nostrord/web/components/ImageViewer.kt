package org.nostr.nostrord.web.components

import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.web.bridge.useStateFlow
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.useEffectOnce
import web.cssom.ClassName

/**
 * App-wide fullscreen image viewer (lightbox). Mirrors the native image-fullscreen: tapping a
 * chat image opens it filling the screen; click the backdrop / close button / Escape to dismiss.
 * Place [ImageViewerHost] once at the app root and call [ImageViewer.show] from anywhere.
 */
object ImageViewer {
    /** [backdrop] is the contrast class ("on-light"/"on-dark") for transparent images, or null. */
    data class Shown(val url: String, val backdrop: String?)

    private val _shown = MutableStateFlow<Shown?>(null)
    val shown: StateFlow<Shown?> = _shown.asStateFlow()

    fun show(imageUrl: String, backdrop: String? = null) {
        _shown.value = Shown(imageUrl, backdrop)
    }

    fun dismiss() {
        _shown.value = null
    }
}

/** Place once at the app root. Renders the fullscreen overlay whenever an image is open. */
val ImageViewerHost =
    FC<Props> {
        val current = useStateFlow(ImageViewer.shown)

        useEffectOnce {
            window.asDynamic().addEventListener("keydown") { event: dynamic ->
                if (event.key == "Escape" && ImageViewer.shown.value != null) ImageViewer.dismiss()
            }
        }

        if (current != null) {
            val url = current.url
            div {
                className = ClassName("img-viewer-overlay")
                onClick = { ImageViewer.dismiss() }
                div {
                    className = ClassName("img-viewer-bar")
                    onClick = { it.stopPropagation() }
                    button {
                        className = ClassName("img-viewer-action")
                        title = "Open in new tab"
                        onClick = { window.open(url, "_blank") }
                        icon(Ic.OpenInNew)
                    }
                    button {
                        className = ClassName("img-viewer-action")
                        title = "Download"
                        onClick = { downloadImage(url) }
                        icon(Ic.Download)
                    }
                    button {
                        className = ClassName("img-viewer-action")
                        title = "Close"
                        onClick = { ImageViewer.dismiss() }
                        icon(Ic.Close)
                    }
                }
                img {
                    className = ClassName("img-viewer-img" + (current.backdrop?.let { " $it" } ?: ""))
                    src = url
                    alt = ""
                    onClick = { it.stopPropagation() }
                }
            }
        }
    }

/** Download [url] as a file. Fetches a blob (so cross-origin images save instead of opening); falls back to a new tab. */
private fun downloadImage(url: String) {
    val name = url.substringBefore('?').substringAfterLast('/').ifBlank { "image" }
    val w = window.asDynamic()
    w.fetch(url)
        .then { resp: dynamic -> resp.blob() }
        .then { blob: dynamic ->
            val objectUrl = w.URL.createObjectURL(blob)
            val anchor = document.createElement("a")
            val a = anchor.asDynamic()
            a.href = objectUrl
            a.download = name
            document.body?.appendChild(anchor)
            a.click()
            anchor.remove()
            w.URL.revokeObjectURL(objectUrl)
        }
        .catch { _: dynamic -> w.open(url, "_blank") }
}
