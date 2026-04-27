package org.nostr.nostrord.notifications

import kotlinx.browser.document

actual fun setDocumentTitle(title: String) {
    document.title = title
}
