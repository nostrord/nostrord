package org.nostr.nostrord.web.components

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
            div {
                className = ClassName("img-viewer-overlay")
                onClick = { ImageViewer.dismiss() }
                button {
                    className = ClassName("img-viewer-close")
                    onClick = {
                        it.stopPropagation()
                        ImageViewer.dismiss()
                    }
                    icon(Ic.Close)
                }
                img {
                    className = ClassName("img-viewer-img" + (current.backdrop?.let { " $it" } ?: ""))
                    src = current.url
                    alt = ""
                    onClick = { it.stopPropagation() }
                }
            }
        }
    }
