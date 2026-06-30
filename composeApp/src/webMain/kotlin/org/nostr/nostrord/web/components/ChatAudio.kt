package org.nostr.nostrord.web.components

import kotlinx.coroutines.awaitCancellation
import react.FC
import react.Props
import react.dom.html.ReactHTML.audio
import react.dom.html.ReactHTML.div
import react.useEffect
import react.useRef
import react.useState
import web.cssom.ClassName
import web.html.HTMLAudioElement

external interface ChatAudioProps : Props {
    var audioUrl: String
}

/**
 * A chat inline audio clip. Renders the native <audio controls> element, which gives
 * play/pause, seek, duration and replay for free (matching the Compose AudioPlayerContent
 * feature set). Honors Settings > Media > Auto-load: when off, a tap-to-load placeholder
 * shows until the user reveals this single clip. The element is released on unmount so
 * switching groups/relays doesn't pile up open media handles.
 */
val ChatAudio =
    FC<ChatAudioProps> { props ->
        val autoLoad = useAutoLoadMedia()
        val (revealed, setRevealed) = useState { false }
        val audioRef = useRef<HTMLAudioElement>(null)

        val showPlayer = autoLoad || revealed

        useEffect(props.audioUrl) {
            try {
                awaitCancellation()
            } finally {
                val node = audioRef.current ?: return@useEffect
                runCatching {
                    node.asDynamic().pause()
                    node.removeAttribute("src")
                    node.asDynamic().load()
                }
            }
        }

        if (!showPlayer) {
            mediaGatePlaceholder("audio") { setRevealed(true) }
        } else {
            div {
                className = ClassName("msg-audio-wrap")
                audio {
                    ref = audioRef
                    className = ClassName("msg-audio")
                    src = props.audioUrl
                    controls = true
                    preload = "metadata"
                }
            }
        }
    }
