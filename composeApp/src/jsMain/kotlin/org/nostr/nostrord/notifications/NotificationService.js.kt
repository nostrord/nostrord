package org.nostr.nostrord.notifications

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.nostr.nostrord.utils.groupDeepLinkQuery

private fun jsSupported(): Boolean = js("typeof Notification !== 'undefined'").unsafeCast<Boolean>()

private fun jsGetPermission(): String = js("Notification.permission").unsafeCast<String>()

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
        """,
    )
}

private fun jsBuildOptions(body: String, tag: String, iconUrl: String, deepLink: String): dynamic {
    val opts = js("({})")
    opts.body = body
    opts.tag = tag
    if (iconUrl.isNotEmpty()) opts.icon = iconUrl
    // The service-worker notificationclick handler reads data.url to route the click.
    val data = js("({})")
    data.url = deepLink
    opts.data = data
    return opts
}

// Prefer the service worker: notifications it shows survive in the OS tray and their
// clicks are handled by sw.js, which focuses an existing tab (or opens one) at the
// deep link. onUnavailable runs the page-context fallback when no SW is ready.
private fun jsShowViaServiceWorker(title: String, opts: dynamic, onUnavailable: () -> Unit) {
    val ready = js("(typeof navigator !== 'undefined' && navigator.serviceWorker && navigator.serviceWorker.ready) ? navigator.serviceWorker.ready : null")
    if (ready == null) {
        onUnavailable()
        return
    }
    ready.then({ reg: dynamic ->
        if (reg != null && reg.showNotification != null) {
            reg.showNotification(title, opts)
        } else {
            onUnavailable()
        }
        null
    }).catch({ _: dynamic ->
        onUnavailable()
        null
    })
}

// Fallback for browsers without an active service worker: a page-context
// Notification. Its click can only act on this tab, so it focuses and emits a
// NotificationClick that the web UI consumes to navigate in place.
private fun jsShowPageNotification(title: String, opts: dynamic, onClick: () -> Unit): dynamic = try {
    val notification = js("new Notification(title, opts)")
    notification.onclick = {
        js("try { window.focus(); } catch (e) {}")
        notification.close()
        onClick()
    }
    notification
} catch (_: Throwable) {
    null
}

private fun jsCloseNotification(handle: dynamic) {
    try {
        handle.close()
    } catch (_: Throwable) {}
}

// Close service-worker notifications whose tag is in [tags] (an array passed to JS as-is).
private fun jsCloseServiceWorkerNotifications(tags: Array<String>) {
    js(
        """
        try {
            if (typeof navigator !== 'undefined' && navigator.serviceWorker && navigator.serviceWorker.ready) {
                navigator.serviceWorker.ready.then(function(reg) {
                    if (reg && reg.getNotifications) {
                        reg.getNotifications().then(function(list) {
                            list.forEach(function(n) {
                                if (tags.indexOf(n.tag) !== -1) { try { n.close(); } catch (e) {} }
                            });
                        });
                    }
                });
            }
        } catch (e) {}
        """,
    )
}

actual class NotificationService actual constructor() {
    private val _permission = MutableStateFlow(readPermission())
    actual val permission: StateFlow<NotificationPermission> = _permission.asStateFlow()

    private val _clicks = MutableSharedFlow<NotificationClick>(extraBufferCapacity = 8)
    actual val notificationClicks: SharedFlow<NotificationClick> = _clicks.asSharedFlow()

    // Tracks notifications currently surfaced so [cancelAllPending] can close
    // them on account switch. Bounded so a long-running session can't grow it
    // without limit; oldest entries fall off when the list crosses MAX_TRACKED.
    private val activeNotifications = ArrayDeque<dynamic>()
    private val MAX_TRACKED = 100

    // Tags of notifications shown via the service worker (which returns no closable
    // handle). cancelAllPending closes only these on account switch, so it never reaches
    // across to notifications another account/tab posted through the shared registration.
    private val swNotificationTags = ArrayDeque<String>()

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
        val opts = jsBuildOptions(request.body, request.tag, request.iconUrl ?: "", buildDeepLink(request))
        // Track the tag up front: the SW path shows asynchronously and returns no handle,
        // so this is the only record cancelAllPending can use to close it later.
        swNotificationTags.addLast(request.tag)
        while (swNotificationTags.size > MAX_TRACKED) swNotificationTags.removeFirst()
        jsShowViaServiceWorker(request.title, opts) {
            val handle = jsShowPageNotification(request.title, opts) {
                _clicks.tryEmit(NotificationClick(request.relayUrl, request.groupId, request.messageId))
            }
            if (handle != null) {
                activeNotifications.addLast(handle)
                while (activeNotifications.size > MAX_TRACKED) activeNotifications.removeFirst()
            }
        }
    }

    // Deep link the sw.js notificationclick handler routes to. Uses the shared
    // groupDeepLinkQuery contract so the payload, in-app URL writer, and parser never drift.
    private fun buildDeepLink(request: NotificationRequest): String = groupDeepLinkQuery(request.relayUrl, request.groupId, request.messageId)

    actual fun cancelAllPending() {
        if (!jsSupported()) return
        val snapshot = activeNotifications.toList()
        activeNotifications.clear()
        snapshot.forEach { jsCloseNotification(it) }
        // Close service-worker notifications we showed (e.g. on account switch), scoped to
        // our own tags so we don't close notifications another account/tab posted through the
        // shared registration.
        val tags = swNotificationTags.toTypedArray()
        swNotificationTags.clear()
        if (tags.isNotEmpty()) jsCloseServiceWorkerNotifications(tags)
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
