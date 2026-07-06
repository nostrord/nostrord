package org.nostr.nostrord.utils

import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970

actual fun epochMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun timestampToDateTime(epochSeconds: Long): SimpleDateTime {
    val dt =
        Instant
            .fromEpochSeconds(epochSeconds)
            .toLocalDateTime(TimeZone.currentSystemDefault())
    return SimpleDateTime(
        year = dt.year,
        month = dt.monthNumber,
        day = dt.dayOfMonth,
        hour = dt.hour,
        minute = dt.minute,
        second = dt.second,
    )
}
