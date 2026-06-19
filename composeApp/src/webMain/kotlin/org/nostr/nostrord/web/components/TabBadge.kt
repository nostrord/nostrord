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
 * Overlays [count] on the favicon and prefixes `document.title` with "(N) " whenever
 * there are unread messages, and restores the plain icon/title at zero (and on unmount).
 * The badge persists until the count itself drops to zero, which happens through the
 * normal read path (focus + reaching the bottom), so it naturally clears once the user
 * has caught up. Count is capped at "99+".
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
            setFavicon(drawBadge(cached, label))
        } else {
            val img = js("new Image()")
            img.onload = {
                cachedIcon = img
                setFavicon(drawBadge(img, label))
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

/** Draw [label] in a red pill over [baseImg] and return a PNG data URL. */
private fun drawBadge(baseImg: dynamic, label: String): String {
    val size = 64
    val canvas = document.createElement("canvas")
    canvas.asDynamic().width = size
    canvas.asDynamic().height = size
    val ctx = canvas.asDynamic().getContext("2d")
    ctx.clearRect(0, 0, size, size)
    ctx.drawImage(baseImg, 0, 0, size, size)

    // Bottom-right badge. A wider pill for 2-3 glyphs ("12", "99+"), a circle for one.
    val r = 22.0
    val cy = size - r
    val cx = size - r
    ctx.fillStyle = "#ef4444"
    if (label.length > 1) {
        val halfW = 6.0 * label.length
        roundedPill(ctx, cx - halfW, cy - r, halfW * 2 + r, r * 2, r)
    } else {
        ctx.beginPath()
        ctx.arc(cx, cy, r, 0.0, 2 * PI)
        ctx.fill()
    }
    ctx.fillStyle = "#ffffff"
    val fontPx = if (label.length > 2) 22 else if (label.length > 1) 28 else 34
    ctx.font = "bold ${fontPx}px sans-serif"
    ctx.textAlign = "center"
    ctx.textBaseline = "middle"
    ctx.fillText(label, cx, cy + 2.0)
    return canvas.asDynamic().toDataURL("image/png") as String
}

private fun roundedPill(ctx: dynamic, x: Double, y: Double, w: Double, h: Double, radius: Double) {
    ctx.beginPath()
    ctx.moveTo(x + radius, y)
    ctx.arcTo(x + w, y, x + w, y + h, radius)
    ctx.arcTo(x + w, y + h, x, y + h, radius)
    ctx.arcTo(x, y + h, x, y, radius)
    ctx.arcTo(x, y, x + w, y, radius)
    ctx.closePath()
    ctx.fill()
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
