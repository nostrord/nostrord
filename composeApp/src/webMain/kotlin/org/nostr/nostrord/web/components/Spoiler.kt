package org.nostr.nostrord.web.components

import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.useState
import web.cssom.ClassName

external interface SpoilerProps : Props {
    var content: String
}

/**
 * Discord-style spoiler (||text||): blurred until clicked, click toggles. Mirrors the
 * native MessageContent spoiler and the prototype Spoiler component. stopPropagation so
 * revealing a spoiler doesn't also trigger the surrounding message-row handlers.
 */
val Spoiler =
    FC<SpoilerProps> { props ->
        val (shown, setShown) = useState { false }
        button {
            className = ClassName(if (shown) "msg-spoiler shown" else "msg-spoiler")
            title = if (shown) "Hide spoiler" else "Reveal spoiler"
            onClick = { e ->
                e.stopPropagation()
                setShown(!shown)
            }
            +props.content
        }
    }
