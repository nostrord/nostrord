package org.nostr.nostrord.ui.components.onboarding

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.buttons.FollowButton
import org.nostr.nostrord.ui.screens.onboarding.OnboardingFollowSuggestion
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

/**
 * Onboarding building blocks — Compose counterpart of the web's `.onb-*` /
 * `.hint-row` component classes (prototype Onboarding page).
 */

/** Step progress bars: one pill per step, filled up to (and including) [current]. */
@Composable
fun StepProgress(
    current: Int,
    total: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (step in 0 until total) {
            Box(
                modifier =
                Modifier
                    .weight(1f)
                    .height(6.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(if (step <= current) NostrordColors.Primary else NostrordColors.InputBackground),
            )
        }
    }
}

/** Uppercase step label under the bars: "Step 1 of 2 · Welcome". */
@Composable
fun StepLabel(
    current: Int,
    total: Int,
    name: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = "Step ${current + 1} of $total · $name".uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = NostrordColors.TextMuted,
        modifier = modifier,
    )
}

/**
 * A single "person to follow" row for the onboarding's "Who to follow" step and the
 * Home "People" filter: avatar, name, a short note, and a Follow / Following toggle.
 * Manages its own publish-in-flight state; wired to the real NIP-02 follow actions.
 */
@Composable
fun FollowSuggestionRow(
    person: OnboardingFollowSuggestion,
    pictureUrl: String?,
    isFollowing: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeLarge),
        shape = NostrordShapes.shapeLarge,
        color = NostrordColors.Surface,
        border = BorderStroke(1.dp, NostrordColors.Divider),
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProfileAvatar(
                imageUrl = pictureUrl,
                displayName = person.name,
                pubkey = person.pubkey,
                size = 44.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    person.name,
                    color = NostrordColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    person.note,
                    color = NostrordColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            // Fire-and-forget on the app scope so the publish survives leaving this screen;
            // the repository flips `following` optimistically, so the button updates at once.
            FollowButton(
                isFollowing = isFollowing,
                isBusy = false,
                onToggle = {
                    if (person.pubkey.isBlank()) return@FollowButton
                    AppModule.launchApp {
                        val repo = AppModule.nostrRepository
                        if (isFollowing) repo.unfollowUser(person.pubkey) else repo.followUser(person.pubkey)
                    }
                },
            )
        }
    }
}

/**
 * "Follow all" action above a [FollowSuggestionRow] list. Publishes a single kind:3
 * with every not-yet-followed pubkey; reads as "Following all" once they all are.
 */
@Composable
fun FollowAllButton(
    people: List<OnboardingFollowSuggestion>,
    following: Set<String>,
    modifier: Modifier = Modifier,
) {
    val pending = people.map { it.pubkey }.filter { it.isNotBlank() && it !in following }
    AppButton(
        text = if (pending.isEmpty()) "Following all" else "Follow all",
        onClick = {
            val batch = pending.toSet()
            // App scope + optimistic flip: button reads "Following all" instantly and the
            // single kind:3 publishes in the background, surviving a quick navigation away.
            AppModule.launchApp { AppModule.nostrRepository.followUsers(batch) }
        },
        enabled = pending.isNotEmpty(),
        variant = AppButtonVariant.Secondary,
        modifier = modifier,
    )
}

/** Icon + title + description row on a surface card (prototype's welcome hints). */
@Composable
fun HintRow(
    icon: ImageVector,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = NostrordShapes.shapeMedium,
        color = NostrordColors.Surface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NostrordColors.Primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    title,
                    color = NostrordColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    description,
                    color = NostrordColors.TextMuted,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
