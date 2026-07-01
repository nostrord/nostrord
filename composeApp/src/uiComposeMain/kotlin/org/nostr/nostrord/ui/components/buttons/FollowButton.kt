package org.nostr.nostrord.ui.components.buttons

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier

/**
 * The Follow / Following toggle shared by the profile page and the quick profile
 * modal. Not following → primary "Follow" with a +. Following → secondary
 * "Following" that flips to a danger "Unfollow" on hover (so the destructive
 * action is explicit), matching the web `.follow-toggle` CSS. While a publish is
 * in flight it shows the AppButton spinner and is disabled.
 */
@Composable
fun FollowButton(
    isFollowing: Boolean,
    isBusy: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val showUnfollow = isFollowing && hovered

    AppButton(
        text =
        when {
            !isFollowing -> "Follow"
            showUnfollow -> "Unfollow"
            else -> "Following"
        },
        onClick = onToggle,
        enabled = !isBusy,
        loading = isBusy,
        variant =
        when {
            !isFollowing -> AppButtonVariant.Primary
            showUnfollow -> AppButtonVariant.Danger
            else -> AppButtonVariant.Secondary
        },
        icon = if (isFollowing) null else Icons.Default.Add,
        modifier = modifier.hoverable(interaction),
    )
}
