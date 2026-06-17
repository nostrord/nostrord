package org.nostr.nostrord.web.components

import org.nostr.nostrord.network.GroupMetadata
import react.ChildrenBuilder
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * NIP-29 access-tag pills (kind:39000) reusing the shared `info-badge` object:
 * Public green / Private yellow, Open purple / Closed orange. Same tones as the
 * group info modal and the discovery cards. Wrap in an `info-badges` flex
 * container at the call site.
 */
fun ChildrenBuilder.groupTypeBadges(meta: GroupMetadata) {
    span {
        className = ClassName(if (meta.isPublic) "info-badge success" else "info-badge warning")
        +(if (meta.isPublic) "Public" else "Private")
    }
    span {
        className = ClassName(if (meta.isOpen) "info-badge primary" else "info-badge orange")
        +(if (meta.isOpen) "Open" else "Closed")
    }
}
