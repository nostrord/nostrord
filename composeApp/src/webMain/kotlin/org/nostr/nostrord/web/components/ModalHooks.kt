package org.nostr.nostrord.web.components

import kotlinx.coroutines.awaitCancellation
import react.useEffect
import react.useRef
import web.dom.document

/**
 * Closes a modal/overlay when the Escape key is pressed.
 *
 * Registers a single document-level keydown listener for the lifetime of the calling component, so
 * it works regardless of which element currently holds focus (a backdrop `onClick` only fires when
 * the backdrop itself is clicked). The latest [onClose] is read through a ref so the listener never
 * goes stale without re-registering on every render.
 *
 * Cleanup follows this wrappers version's model: `useEffect` runs a suspend block whose scope is
 * cancelled on unmount, so [awaitCancellation] + `finally` removes the listener (same pattern as
 * `useStateFlow`'s `flow.collect`).
 */
fun useEscClose(onClose: () -> Unit) {
    val cb = useRef(onClose)
    cb.current = onClose
    useEffect(Unit) {
        val handler: (dynamic) -> Unit = { e ->
            if (e.key == "Escape") cb.current?.invoke()
        }
        document.asDynamic().addEventListener("keydown", handler)
        try {
            awaitCancellation()
        } finally {
            document.asDynamic().removeEventListener("keydown", handler)
        }
    }
}
