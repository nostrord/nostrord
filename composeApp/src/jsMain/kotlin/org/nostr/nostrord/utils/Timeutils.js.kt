package org.nostr.nostrord.utils

import kotlin.js.Date

actual fun epochMillis(): Long = Date.now().toLong()

actual fun timestampToDateTime(epochSeconds: Long): SimpleDateTime {
    val date = Date(epochSeconds * 1000.0)

    return SimpleDateTime(
        year = date.getFullYear(),
        month = date.getMonth() + 1,
        day = date.getDate(),
        hour = date.getHours(),
        minute = date.getMinutes(),
        second = date.getSeconds(),
    )
}
