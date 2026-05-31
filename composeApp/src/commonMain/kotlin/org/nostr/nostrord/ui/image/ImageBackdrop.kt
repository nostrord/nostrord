package org.nostr.nostrord.ui.image

/**
 * Which contrasting backdrop a transparent chat image needs so its content stays
 * visible on the chat surface. [OnLight] = dark content over a white backdrop,
 * [OnDark] = light content over a dark backdrop.
 */
enum class ImageBackdrop { OnLight, OnDark }

/**
 * Decide the backdrop for an image from a set of sampled ARGB pixels (0xAARRGGBB).
 *
 * Pure, platform-agnostic mirror of the web's `ChatImage.analyzeBackdrop`, shared by
 * native and (eventually) web so both decide identically. The caller samples the
 * decoded bitmap (any grid/stride) and passes the pixels here.
 *
 * Returns null when the image is effectively opaque (under 5% transparent pixels) or
 * has no readable opaque pixels; otherwise [ImageBackdrop.OnLight] when the opaque
 * content is dark (mean luma < 128) and [ImageBackdrop.OnDark] when it is light.
 */
fun decideImageBackdrop(argb: IntArray): ImageBackdrop? {
    val total = argb.size
    if (total == 0) return null
    var transparent = 0
    var opaque = 0
    var lumaSum = 0.0
    for (c in argb) {
        val alpha = (c ushr 24) and 0xFF
        if (alpha < 240) transparent++
        if (alpha > 200) {
            val r = (c ushr 16) and 0xFF
            val g = (c ushr 8) and 0xFF
            val b = c and 0xFF
            lumaSum += 0.299 * r + 0.587 * g + 0.114 * b
            opaque++
        }
    }
    // Mostly opaque (or unreadable) -> leave the image as-is.
    if (transparent < total * 0.05 || opaque == 0) return null
    return if (lumaSum / opaque < 128.0) ImageBackdrop.OnLight else ImageBackdrop.OnDark
}
