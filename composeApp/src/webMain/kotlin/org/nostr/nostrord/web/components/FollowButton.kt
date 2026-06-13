package org.nostr.nostrord.web.components

import react.ChildrenBuilder
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.span
import web.cssom.ClassName

/**
 * The Follow / Following toggle shared by the profile page and the quick profile
 * modal. Not following -> primary "Follow" with a +. Following -> secondary
 * "Following" that flips to a danger "Unfollow" on hover (CSS `.follow-toggle`,
 * mirroring the native FollowButton). While a publish is in flight it shows the
 * inline spinner and is disabled.
 */
fun ChildrenBuilder.followToggleButton(
    isFollowing: Boolean,
    isBusy: Boolean,
    onToggle: () -> Unit,
) {
    button {
        className =
            ClassName(
                if (isFollowing) "btn-secondary profile-btn follow-toggle following" else "btn-primary profile-btn follow-toggle",
            )
        disabled = isBusy
        onClick = { onToggle() }
        when {
            isBusy -> span { className = ClassName("btn-spinner") }
            !isFollowing -> icon(Ic.Add)
        }
        if (isFollowing) {
            span {
                className = ClassName("label-following")
                +"Following"
            }
            span {
                className = ClassName("label-unfollow")
                +"Unfollow"
            }
        } else {
            +"Follow"
        }
    }
}
