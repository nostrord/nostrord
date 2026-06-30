package org.nostr.nostrord.web.components

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.HTMLTextAreaElement

/**
 * Copy [text] to the system clipboard.
 *
 * The async Clipboard API (`navigator.clipboard.writeText`) only works in
 * secure contexts (HTTPS / `localhost`) and from a user-gesture handler. On
 * plain HTTP, in some iframes, or when the user has denied the permission
 * the API is either missing or its promise rejects.
 *
 * This helper tries the async path first and, on rejection or absence,
 * falls back to a transient hidden `<textarea>` + `document.execCommand`,
 * which works in every modern browser as long as the call is reached from
 * within a click handler.
 */
fun copyToClipboard(text: String) {
    if (text.isEmpty()) return
    val clip = window.navigator.asDynamic().clipboard
    if (clip != null && clip.writeText != null) {
        try {
            val promise = clip.writeText(text)
            if (promise != null && promise.then != null) {
                promise.then(
                    { /* ok */ },
                    { _: dynamic -> legacyCopy(text) },
                )
                return
            }
            // No promise returned, treat as success.
            return
        } catch (_: Throwable) {
            // Fall through to the legacy path.
        }
    }
    legacyCopy(text)
}

/** Hidden-textarea + execCommand fallback for insecure contexts / older browsers. */
private fun legacyCopy(text: String) {
    val body = document.body ?: return
    val ta = document.createElement("textarea") as HTMLTextAreaElement
    ta.value = text
    // Off-screen but still focusable and selectable.
    ta.style.position = "fixed"
    ta.style.left = "-9999px"
    ta.style.top = "0"
    ta.style.opacity = "0"
    ta.setAttribute("readonly", "")
    body.appendChild(ta)
    try {
        ta.select()
        ta.setSelectionRange(0, text.length)
        document.asDynamic().execCommand("copy")
    } catch (_: Throwable) {
        // Browser blocked the synchronous path too. Nothing else we can do.
    } finally {
        body.removeChild(ta)
    }
}
