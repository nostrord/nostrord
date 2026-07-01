package org.nostr.nostrord.web.components

import kotlinx.browser.document
import kotlinx.coroutines.awaitCancellation
import react.useEffect
import kotlin.math.PI

// The plain app title and the favicon shipped in resources/index.html. The badge is
// drawn ON TOP of this icon at runtime (canvas), so no extra image asset is needed.
private const val BASE_TITLE = "Nostrord"
private const val BASE_ICON = "./icon-192.png"

// Cache the decoded base favicon across renders/components so the count only triggers a
// redraw, never a refetch. One tab, one app, so a module-level cache is enough.
private var cachedIcon: dynamic = null

/**
 * Telegram-style browser-tab unread badge (web only; native uses OS notifications).
 *
 * Marks the favicon with a red unread DOT (no number: a digit is illegible at favicon
 * size) and prefixes `document.title` with the exact count "(N) " whenever there are
 * unread messages, and restores the plain icon/title at zero (and on unmount). The badge
 * persists until the count itself drops to zero, which happens through the normal read
 * path (focus + reaching the bottom), so it naturally clears once the user has caught up.
 * The title count is capped at "99+".
 */
fun useTabBadge(count: Int) {
    useEffect(count) {
        val label = if (count > 99) "99+" else count.toString()
        document.title = if (count > 0) "($label) $BASE_TITLE" else BASE_TITLE
        if (count <= 0) {
            setFavicon(BASE_ICON)
            return@useEffect
        }
        val cached = cachedIcon
        if (cached != null) {
            setFavicon(drawDot(cached))
        } else {
            val img = js("new Image()")
            img.onload = {
                cachedIcon = img
                setFavicon(drawDot(img))
            }
            img.src = BASE_ICON
        }
    }

    // Restore the plain title/icon when the badge owner (the logged-in shell) unmounts,
    // e.g. on logout, so a stale "(3) Nostrord" can't linger on the login screen.
    useEffect(Unit) {
        try {
            awaitCancellation()
        } finally {
            document.title = BASE_TITLE
            setFavicon(BASE_ICON)
        }
    }
}

/**
 * Draw a plain red unread dot over [baseImg] and return a PNG data URL. No number: at
 * favicon size a digit is illegible, so the dot just signals "unread" and the exact
 * count rides in the tab title. A thin background-coloured ring lifts the dot off the
 * icon so it reads at 16px.
 */
private fun drawDot(baseImg: dynamic): String {
    val size = 64
    val canvas = document.createElement("canvas")
    canvas.asDynamic().width = size
    canvas.asDynamic().height = size
    val ctx = canvas.asDynamic().getContext("2d")
    ctx.clearRect(0, 0, size, size)
    ctx.drawImage(baseImg, 0, 0, size, size)

    // Top-right dot, the corner that stays visible when the OS clips the favicon.
    val r = 16.0
    val cx = size - r - 2.0
    val cy = r + 2.0
    ctx.beginPath()
    ctx.arc(cx, cy, r + 3.0, 0.0, 2 * PI)
    ctx.fillStyle = "#ffffff"
    ctx.fill()
    ctx.beginPath()
    ctx.arc(cx, cy, r, 0.0, 2 * PI)
    ctx.fillStyle = "#ef4444"
    ctx.fill()
    return canvas.asDynamic().toDataURL("image/png") as String
}

private fun setFavicon(href: String) {
    val links = document.querySelectorAll("link[rel~='icon']")
    if (links.length > 0) {
        for (i in 0 until links.length) {
            links.item(i)?.asDynamic()?.href = href
        }
    } else {
        val link = document.createElement("link")
        link.asDynamic().rel = "icon"
        link.asDynamic().href = href
        document.head?.appendChild(link)
    }
}
