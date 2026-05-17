// wasmJsMain/kotlin/org/nostr/nostrord/utils/TimeUtils.kt
package org.nostr.nostrord.utils

// Use @JsFun for proper Wasm interop
@JsFun("() => Date.now()")
private external fun jsDateNow(): Double

@JsFun("(millis) => new Date(millis).getFullYear()")
private external fun jsGetFullYear(millis: Double): Int

@JsFun("(millis) => new Date(millis).getMonth()")
private external fun jsGetMonth(millis: Double): Int

@JsFun("(millis) => new Date(millis).getDate()")
private external fun jsGetDate(millis: Double): Int

@JsFun("(millis) => new Date(millis).getHours()")
private external fun jsGetHours(millis: Double): Int

@JsFun("(millis) => new Date(millis).getMinutes()")
private external fun jsGetMinutes(millis: Double): Int

@JsFun("(millis) => new Date(millis).getSeconds()")
private external fun jsGetSeconds(millis: Double): Int

actual fun epochMillis(): Long = jsDateNow().toLong()

actual fun timestampToDateTime(epochSeconds: Long): SimpleDateTime {
    val millis = (epochSeconds * 1000).toDouble()

    return SimpleDateTime(
        year = jsGetFullYear(millis),
        month = jsGetMonth(millis) + 1,
        day = jsGetDate(millis),
        hour = jsGetHours(millis),
        minute = jsGetMinutes(millis),
        second = jsGetSeconds(millis),
    )
}
