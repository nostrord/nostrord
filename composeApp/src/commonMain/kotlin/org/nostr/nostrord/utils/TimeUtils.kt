package org.nostr.nostrord.utils

expect fun epochMillis(): Long

fun epochSeconds(): Long = epochMillis() / 1000

data class SimpleDateTime(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
    val second: Int
)

expect fun timestampToDateTime(epochSeconds: Long): SimpleDateTime

fun getDateLabel(timestamp: Long): String {
    val now = epochSeconds()
    val nowDateTime = timestampToDateTime(now)
    val messageDateTime = timestampToDateTime(timestamp)

    val daysDiff = calculateDaysDiff(nowDateTime, messageDateTime)
    val monthNames = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    val monthName = monthNames.getOrNull(messageDateTime.month - 1) ?: messageDateTime.month.toString()

    return when {
        daysDiff == 0 -> "Today"
        daysDiff == 1 -> "Yesterday"
        messageDateTime.year != nowDateTime.year -> {
            "${messageDateTime.day} $monthName ${messageDateTime.year}"
        }
        else -> {
            "${messageDateTime.day} $monthName"
        }
    }
}

fun formatTime(timestamp: Long): String {
    val now = epochSeconds()
    val nowDateTime = timestampToDateTime(now)
    val dateTime = timestampToDateTime(timestamp)
    val hour = dateTime.hour.toString().padStart(2, '0')
    val minute = dateTime.minute.toString().padStart(2, '0')

    return if (dateTime.year != nowDateTime.year) {
        // Show date and year for messages from previous years
        val day = dateTime.day.toString().padStart(2, '0')
        val month = dateTime.month.toString().padStart(2, '0')
        "$day/$month/${dateTime.year} $hour:$minute"
    } else {
        "$hour:$minute"
    }
}

fun formatTimestamp(timestamp: Long): String {
    val now = epochSeconds()
    val diff = now - timestamp
    
    return when {
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        else -> "${diff / 86400}d ago"
    }
}

private fun calculateDaysDiff(date1: SimpleDateTime, date2: SimpleDateTime): Int {
    // Simple day difference calculation
    val days1 = date1.year * 365 + date1.month * 30 + date1.day
    val days2 = date2.year * 365 + date2.month * 30 + date2.day
    
    return kotlin.math.abs(days1 - days2)
}
