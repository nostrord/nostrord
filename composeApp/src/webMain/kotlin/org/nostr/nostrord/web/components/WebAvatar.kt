package org.nostr.nostrord.web.components

import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.useEffect
import react.useState
import web.cssom.ClassName

external interface WebAvatarProps : Props {
    /** Image URL; falls back to the [name] initial when null/blank or it fails to load. */
    var url: String?
    var name: String

    /** Size/shape classes applied alongside `avatar-tile` (e.g. "msg-avatar"). */
    var cls: String
    var onClick: (() -> Unit)?
}

/**
 * Avatar that renders the real picture when available and falls back to a letter tile
 * (matching the `avatar-fallback` style) when there's no URL or the image fails to load.
 */
val WebAvatar =
    FC<WebAvatarProps> { props ->
        val (failed, setFailed) = useState { false }
        useEffect(props.url) { setFailed(false) }

        val url = props.url
        if (!url.isNullOrBlank() && !failed) {
            img {
                className = ClassName("avatar-tile ${props.cls} avatar-img")
                src = url
                alt = props.name
                onError = { setFailed(true) }
                props.onClick?.let { cb -> onClick = { cb() } }
            }
        } else {
            div {
                className = ClassName("avatar-tile ${props.cls} avatar-fallback")
                props.onClick?.let { cb -> onClick = { cb() } }
                +props.name.take(1).uppercase()
            }
        }
    }
