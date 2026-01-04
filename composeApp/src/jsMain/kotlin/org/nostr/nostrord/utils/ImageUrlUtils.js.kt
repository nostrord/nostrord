package org.nostr.nostrord.utils

actual fun getImageUrl(url: String): String {
    if (!isExternalUrl(url)) return url
    val encodedUrl = js("encodeURIComponent(url)").unsafeCast<String>()
    return "https://wsrv.nl/?url=$encodedUrl"
}
