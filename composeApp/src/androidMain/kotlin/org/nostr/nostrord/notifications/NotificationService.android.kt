package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

actual class NotificationService actual constructor() {
    private val _permission = MutableStateFlow(NotificationPermission.Denied)
    actual val permission: StateFlow<NotificationPermission> = _permission.asStateFlow()

    private val _clicks = MutableSharedFlow<NotificationClick>(extraBufferCapacity = 8)
    actual val notificationClicks: SharedFlow<NotificationClick> = _clicks.asSharedFlow()

    actual fun isSupported(): Boolean = false
    actual fun requestPermission() {}
    actual fun notify(request: NotificationRequest) {}
}
