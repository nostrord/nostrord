package org.nostr.nostrord.utils

actual fun getImageUrl(url: String): String {
    if (!isExternalUrl(url)) return url
    val encodedUrl = js("encodeURIComponent(url)").unsafeCast<String>()
    // output=gif forces wsrv.nl to return the full animated GIF (all frames).
    // Without this, wsrv.nl may transcode to a static format, stripping animation.
    val gifSuffix = if (isGifUrl(url)) "&output=gif" else ""
    return "https://wsrv.nl/?url=$encodedUrl$gifSuffix"
}
