package org.nostr.nostrord.ui.components.loading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Skeleton loader for group cards on the home screen.
 * Matches the row-style layout of GroupCard component.
 */
@Composable
fun GroupCardSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(NostrordColors.Surface)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar skeleton
        Box(
            modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .shimmerEffect(),
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Text content
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            // Group name skeleton
            Box(
                modifier =
                Modifier
                    .width(140.dp)
                    .height(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerEffect(),
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Description skeleton
            Box(
                modifier =
                Modifier
                    .fillMaxWidth(0.7f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .shimmerEffect(),
            )
        }
    }
}

/**
 * Skeleton loader for member list items.
 */
@Composable
fun MemberSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Avatar
        Box(
            modifier =
            Modifier
                .size(32.dp)
                .clip(CircleShape)
                .shimmerEffect(),
        )

        Spacer(modifier = Modifier.width(12.dp))

        // Name
        Box(
            modifier =
            Modifier
                .width(100.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .shimmerEffect(),
        )
    }
}

/**
 * Generic skeleton line for text placeholders.
 */
@Composable
fun SkeletonLine(
    width: Dp = 100.dp,
    height: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
        modifier
            .width(width)
            .height(height)
            .clip(RoundedCornerShape(4.dp))
            .shimmerEffect(),
    )
}

/**
 * Generic skeleton circle for avatar placeholders.
 */
@Composable
fun SkeletonCircle(
    size: Dp = 40.dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
        modifier
            .size(size)
            .clip(CircleShape)
            .shimmerEffect(),
    )
}
