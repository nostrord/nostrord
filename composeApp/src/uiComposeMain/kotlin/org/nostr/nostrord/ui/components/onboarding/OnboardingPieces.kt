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
 * Follow-pack card (prototype "Quem seguir"): emoji tile, name, description and a
 * people count, with the trailing "View people" chip. Layout-only: [onClick] is a
 * no-op until the real follow-pack flow lands.
 */
@Composable
fun PackCard(
    emoji: String,
    name: String,
    description: String,
    people: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Surface(
        modifier =
        modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeLarge),
        shape = NostrordShapes.shapeLarge,
        color = NostrordColors.Surface,
        border = BorderStroke(1.dp, NostrordColors.Divider),
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier =
                Modifier
                    .size(48.dp)
                    .clip(NostrordShapes.shapeXLarge)
                    .background(NostrordColors.BackgroundFloating),
                contentAlignment = Alignment.Center,
            ) {
                Text(emoji, fontSize = 24.sp)
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    name,
                    color = NostrordColors.TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    description,
                    color = NostrordColors.TextSecondary,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "$people people",
                    color = NostrordColors.TextMuted,
                    fontSize = 12.sp,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Surface(
                shape = NostrordShapes.shapeMedium,
                color = NostrordColors.InputBackground,
            ) {
                Text(
                    "View people ›",
                    color = NostrordColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
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
