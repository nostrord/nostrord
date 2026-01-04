package org.nostr.nostrord.utils

@JsFun("(url) => encodeURIComponent(url)")
private external fun jsEncodeURIComponent(url: String): String

actual fun getImageUrl(url: String): String {
    if (!isExternalUrl(url)) return url
    val encodedUrl = jsEncodeURIComponent(url)
    return "https://wsrv.nl/?url=$encodedUrl"
}
