package org.nostr.nostrord.web.components

import org.nostr.nostrord.network.GroupMetadata
import react.ChildrenBuilder
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * NIP-29 access-tag pills (kind:39000) reusing the shared `info-badge` object:
 * Public green / Private yellow, Open purple / Closed orange, plus the secondary
 * Restricted (danger) and Hidden (info) attributes shown only when set. Same tones
 * as the group info modal. Wrap in an `info-badges` flex container at the call site.
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
    // Secondary attributes: Restricted = only members can post, Hidden = relay hides
    // metadata from non-members. Shown only when present so common groups stay uncluttered.
    if (meta.isRestricted) {
        span {
            className = ClassName("info-badge danger")
            +"Restricted"
        }
    }
    if (meta.isHidden) {
        span {
            className = ClassName("info-badge info")
            +"Hidden"
        }
    }
}
