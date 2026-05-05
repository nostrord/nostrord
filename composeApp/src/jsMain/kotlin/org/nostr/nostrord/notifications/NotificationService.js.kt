package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

private fun jsSupported(): Boolean =
    js("typeof Notification !== 'undefined'").unsafeCast<Boolean>()

private fun jsGetPermission(): String =
    js("Notification.permission").unsafeCast<String>()

private fun jsRequestPermission(cb: (String) -> Unit) {
    val promise = js("Notification.requestPermission()")
    promise.then({ result: dynamic ->
        cb(result.toString())
        null
    })
}

private fun jsObservePermissionChanges(cb: (String) -> Unit) {
    js(
        """
        try {
            if (navigator && navigator.permissions && navigator.permissions.query) {
                navigator.permissions.query({ name: 'notifications' }).then(function(status) {
                    cb(status.state);
                    status.onchange = function() { cb(status.state); };
                }).catch(function() {});
            }
        } catch (e) {}
        """
    )
}

private fun jsShowNotification(
    title: String,
    body: String,
    tag: String,
    iconUrl: String,
    onClick: () -> Unit,
) {
    val opts = js("({})")
    opts.body = body
    opts.tag = tag
    if (iconUrl.isNotEmpty()) opts.icon = iconUrl
    val notification = js("new Notification(title, opts)")
    notification.onclick = {
        js("window.focus()")
        notification.close()
        onClick()
    }
}

actual class NotificationService actual constructor() {
    private val _permission = MutableStateFlow(readPermission())
    actual val permission: StateFlow<NotificationPermission> = _permission.asStateFlow()

    private val _clicks = MutableSharedFlow<NotificationClick>(extraBufferCapacity = 8)
    actual val notificationClicks: SharedFlow<NotificationClick> = _clicks.asSharedFlow()

    init {
        if (jsSupported()) {
            jsObservePermissionChanges { result ->
                _permission.value = parsePermission(result)
            }
        }
    }

    actual fun isSupported(): Boolean = jsSupported()

    actual fun requestPermission() {
        if (!jsSupported()) return
        if (_permission.value != NotificationPermission.Default) return
        jsRequestPermission { result ->
            _permission.value = parsePermission(result)
        }
    }

    actual fun notify(request: NotificationRequest) {
        if (!jsSupported()) return
        if (_permission.value != NotificationPermission.Granted) return
        jsShowNotification(
            request.title,
            request.body,
            request.tag,
            request.iconUrl ?: "",
        ) { _clicks.tryEmit(NotificationClick(request.relayUrl, request.groupId)) }
    }

    private fun readPermission(): NotificationPermission {
        if (!jsSupported()) return NotificationPermission.Denied
        return parsePermission(jsGetPermission())
    }

    private fun parsePermission(s: String): NotificationPermission = when (s) {
        "granted" -> NotificationPermission.Granted
        "denied" -> NotificationPermission.Denied
        else -> NotificationPermission.Default
    }
}
