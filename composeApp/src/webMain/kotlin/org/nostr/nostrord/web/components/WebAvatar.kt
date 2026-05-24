package org.nostr.nostrord.web.components

import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.useEffect
import react.useState
import web.cssom.ClassName

external interface WebAvatarProps : Props {
    /** Image URL; when null/blank or it fails to load, the identicon fallback stays visible. */
    var url: String?
    var name: String

    /**
     * Stable identity used to seed the identicon (pubkey for users, group id for groups), so the
     * fallback matches native. Falls back to [name] when null/blank.
     */
    var seed: String?

    /** Size/shape classes applied alongside `avatar-tile` (e.g. "msg-avatar"). */
    var cls: String
    var onClick: (() -> Unit)?
}

/**
 * Avatar that always shows the Jdenticon identicon first and overlays the real picture once it
 * loads (fading in). If the image is missing or fails to load, the identicon stays — mirroring
 * the native scheme (Jdenticon while loading / on error).
 */
val WebAvatar =
    FC<WebAvatarProps> { props ->
        val (loaded, setLoaded) = useState { false }
        val (failed, setFailed) = useState { false }
        useEffect(props.url) {
            setLoaded(false)
            setFailed(false)
        }

        val url = props.url
        val seed = props.seed?.takeIf { it.isNotBlank() } ?: props.name

        div {
            className = ClassName("avatar-tile ${props.cls} avatar-stack")
            props.onClick?.let { cb -> onClick = { cb() } }

            // Always-present identicon fallback underneath.
            identicon(seed)

            // Real picture on top: hidden until it loads, removed on error so the identicon shows.
            if (!url.isNullOrBlank() && !failed) {
                img {
                    className = ClassName(if (loaded) "avatar-photo loaded" else "avatar-photo")
                    src = url
                    alt = props.name
                    onLoad = { setLoaded(true) }
                    onError = { setFailed(true) }
                }
            }
        }
    }
