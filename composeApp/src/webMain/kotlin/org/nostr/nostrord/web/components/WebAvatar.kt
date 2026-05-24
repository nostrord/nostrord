package org.nostr.nostrord.web.components

import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.img
import react.useEffect
import react.useState
import web.cssom.ClassName
import kotlin.math.abs

/** What the avatar represents — drives the fallback style and shape (mirrors native). */
enum class AvatarKind { USER, GROUP, RELAY }

external interface WebAvatarProps : Props {
    /** Image URL; when null/blank or it fails to load, the fallback stays visible. */
    var url: String?
    var name: String

    /**
     * Stable identity used to seed the fallback (pubkey for users, group/relay id), so it matches
     * native. Falls back to [name] when null/blank.
     */
    var seed: String?

    /**
     * USER → Jdenticon identicon + circle; GROUP → initial letter on a deterministic colour +
     * rounded square; RELAY → initial letter on a colour (shape left to [cls]). Defaults to USER.
     */
    var kind: AvatarKind?

    /** Size/shape classes applied alongside `avatar-tile` (e.g. "msg-avatar"). */
    var cls: String
    var onClick: (() -> Unit)?
}

/**
 * Avatar that always shows the fallback first and overlays the real picture once it loads (fading
 * in); if the image is missing or fails, the fallback stays. Users fall back to a Jdenticon
 * identicon (circle); groups and relays to an initial letter on a deterministic colour, with
 * groups forced to a rounded square — mirroring the native scheme.
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
        val kind = props.kind ?: AvatarKind.USER

        div {
            className = ClassName("avatar-tile ${props.cls} avatar-stack" + if (kind == AvatarKind.GROUP) " group" else "")
            props.onClick?.let { cb -> onClick = { cb() } }

            // Always-present fallback underneath.
            if (kind == AvatarKind.USER) {
                identicon(seed)
            } else {
                letterAvatar(seed, props.name)
            }

            // Real picture on top: hidden until it loads, removed on error so the fallback shows.
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

// Number of colours in the native NostrordColors.AvatarColors palette (see .avatar-color-N CSS).
private const val AVATAR_COLOR_COUNT = 8

/** Initial-letter fallback on a deterministic colour — matches the native group avatar. */
private fun ChildrenBuilder.letterAvatar(seed: String, name: String) {
    val index = abs(seed.hashCode()) % AVATAR_COLOR_COUNT
    div {
        className = ClassName("avatar-letter avatar-color-$index")
        +name.take(1).uppercase()
    }
}
