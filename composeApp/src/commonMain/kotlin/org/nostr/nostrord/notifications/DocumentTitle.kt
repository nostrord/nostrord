package org.nostr.nostrord.notifications

/**
 * Set the browser tab title. Used to surface unread counts like "(3) Nostrord".
 * No-op on non-web platforms.
 */
expect fun setDocumentTitle(title: String)
