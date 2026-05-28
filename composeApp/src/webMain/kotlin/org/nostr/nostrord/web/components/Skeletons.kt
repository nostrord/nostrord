package org.nostr.nostrord.web.components

import react.ChildrenBuilder
import react.dom.html.ReactHTML.div
import web.cssom.ClassName

/** Shimmer skeleton placeholders mirroring the real row/card shapes during loading. */

private fun ChildrenBuilder.skel(cls: String) {
    div { className = ClassName("skel $cls") }
}

/** Home group-picker card placeholder (matches .pick-card). */
fun ChildrenBuilder.groupCardSkeleton() {
    div {
        className = ClassName("skel-pick-card")
        skel("skel-pick-icon")
        div {
            className = ClassName("skel-pick-info")
            skel("skel-line w-40")
            skel("skel-line w-75")
        }
    }
}

/** Groups-sidebar row placeholder (matches .sidebar-group). */
fun ChildrenBuilder.groupNavSkeleton() {
    div {
        className = ClassName("skel-nav-row")
        skel("skel-nav-icon")
        skel("skel-line w-60")
    }
}

/** Member-sidebar row placeholder (matches .member-row). */
fun ChildrenBuilder.memberSkeleton() {
    div {
        className = ClassName("skel-member-row")
        skel("skel-member-avatar")
        skel("skel-line w-50")
    }
}

/** Chat message placeholder (matches .msg first-in-group). */
fun ChildrenBuilder.messageSkeleton() {
    div {
        className = ClassName("skel-msg")
        skel("skel-msg-avatar")
        div {
            className = ClassName("skel-msg-body")
            skel("skel-line w-30")
            skel("skel-line w-80")
            skel("skel-line w-50")
        }
    }
}
