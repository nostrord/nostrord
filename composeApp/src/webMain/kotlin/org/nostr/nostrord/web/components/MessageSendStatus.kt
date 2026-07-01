package org.nostr.nostrord.web.components

import org.nostr.nostrord.network.managers.GroupManager
import react.ChildrenBuilder
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * Optimistic-send status shown under one of the local user's own messages: "Sending..." while in
 * flight, or "Not delivered" with Retry / Dismiss on failure. Delivered (status == null) renders
 * nothing. Shared by chat (MessageRow) and threads so the indicator stays identical.
 */
fun ChildrenBuilder.messageSendStatus(
    status: GroupManager.MessageStatus?,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
) {
    when (status) {
        is GroupManager.MessageStatus.Sending ->
            div {
                className = ClassName("msg-status sending")
                +"Sending..."
            }
        is GroupManager.MessageStatus.Failed ->
            div {
                className = ClassName("msg-status failed")
                span { +"Not delivered" }
                button {
                    className = ClassName("msg-status-action")
                    onClick = { onRetry() }
                    +"Retry"
                }
                button {
                    className = ClassName("msg-status-action dismiss")
                    onClick = { onDismiss() }
                    +"Dismiss"
                }
            }
        null -> {}
    }
}
