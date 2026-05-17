package org.nostr.nostrord.utils

import java.util.Calendar
import java.util.TimeZone

actual fun epochMillis(): Long = System.currentTimeMillis()

actual fun timestampToDateTime(epochSeconds: Long): SimpleDateTime {
    val calendar =
        Calendar.getInstance(TimeZone.getDefault()).apply {
            timeInMillis = epochSeconds * 1000
        }

    return SimpleDateTime(
        year = calendar.get(Calendar.YEAR),
        month = calendar.get(Calendar.MONTH) + 1, // Calendar.MONTH is 0-based
        day = calendar.get(Calendar.DAY_OF_MONTH),
        hour = calendar.get(Calendar.HOUR_OF_DAY),
        minute = calendar.get(Calendar.MINUTE),
        second = calendar.get(Calendar.SECOND),
    )
}
