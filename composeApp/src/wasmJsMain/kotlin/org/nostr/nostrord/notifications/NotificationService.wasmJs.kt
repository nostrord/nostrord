@file:OptIn(ExperimentalWasmJsInterop::class)
package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.js.ExperimentalWasmJsInterop

@JsFun("() => (typeof Notification !== 'undefined')")
private external fun jsSupported(): Boolean

@JsFun("() => (typeof Notification !== 'undefined' ? Notification.permission : 'denied')")
private external fun jsGetPermission(): String

@JsFun(
    """(cb) => {
        Notification.requestPermission().then(function(result) { cb(result); });
    }"""
)
private external fun jsRequestPermission(cb: (String) -> Unit)

@JsFun(
    """(cb) => {
        try {
            if (navigator && navigator.permissions && navigator.permissions.query) {
                navigator.permissions.query({ name: 'notifications' }).then(function(status) {
                    cb(status.state);
                    status.onchange = function() { cb(status.state); };
                }).catch(function() {});
            }
        } catch (e) {}
    }"""
)
private external fun jsObservePermissionChanges(cb: (String) -> Unit)

@JsFun(
    """(title, body, tag, iconUrl, onClick) => {
        var opts = { body: body, tag: tag };
        if (iconUrl) opts.icon = iconUrl;
        var n = new Notification(title, opts);
        n.onclick = function() {
            window.focus();
            n.close();
            onClick();
        };
    }"""
)
private external fun jsShowNotification(
    title: String,
    body: String,
    tag: String,
    iconUrl: String,
    onClick: () -> Unit,
)

actual class NotificationService actual constructor() {
    private val _permission = MutableStateFlow(readPermission())
    actual val permission: StateFlow<NotificationPermission> = _permission.asStateFlow()

    private val _clicks = MutableSharedFlow<String>(extraBufferCapacity = 8)
    actual val notificationClicks: SharedFlow<String> = _clicks.asSharedFlow()

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
        ) { _clicks.tryEmit(request.groupId) }
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
