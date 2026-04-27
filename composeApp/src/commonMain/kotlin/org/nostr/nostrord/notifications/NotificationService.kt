package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

enum class NotificationPermission {
    /** User hasn't decided yet — the app may prompt. */
    Default,
    /** User granted permission; notify() can display notifications. */
    Granted,
    /** User blocked notifications; notify() must not prompt again. */
    Denied,
}

data class NotificationRequest(
    val groupId: String,
    val title: String,
    val body: String,
    /** Tag used by the browser to coalesce notifications from the same conversation. */
    val tag: String = groupId,
    val iconUrl: String? = null,
)

/**
 * Platform abstraction for user-visible notifications.
 *
 * Web (js, wasmJs): uses the browser Notification API.
 * Other platforms: no-op stubs. Push via FCM/APNs requires a server and is out of scope.
 */
expect class NotificationService() {
    val permission: StateFlow<NotificationPermission>

    /** Emits the groupId of the notification the user clicked. */
    val notificationClicks: SharedFlow<String>

    fun isSupported(): Boolean
    fun requestPermission()
    fun notify(request: NotificationRequest)
}
