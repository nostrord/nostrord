package org.nostr.nostrord.ui.components.home

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

/**
 * Home page building blocks — Compose counterpart of the web's `.group-card` /
 * `.empty-card` component classes (prototype Home).
 */

/** Group card for the discovery grids: square avatar, name, members, description, CTA chip. */
@Composable
fun GroupCard(
    name: String,
    description: String?,
    picture: String?,
    groupId: String,
    memberCount: Int,
    restricted: Boolean,
    cta: String,
    ctaPrimary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth().clip(NostrordShapes.shapeLarge),
        shape = NostrordShapes.shapeLarge,
        color = NostrordColors.Surface,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OptimizedSmallAvatar(
                    imageUrl = picture,
                    identifier = groupId,
                    displayName = name,
                    size = 48.dp,
                    shape = NostrordShapes.shapeMedium,
                    isGroup = true,
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        name,
                        color = NostrordColors.TextPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (memberCount > 0) {
                            Icon(
                                imageVector = Icons.Default.People,
                                contentDescription = null,
                                tint = NostrordColors.TextMuted,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "$memberCount",
                                color = NostrordColors.TextMuted,
                                fontSize = 12.sp,
                            )
                        }
                        if (restricted) {
                            if (memberCount > 0) Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = NostrordShapes.shapeSmall,
                                color = NostrordColors.BackgroundFloating,
                            ) {
                                Text(
                                    "restricted",
                                    color = NostrordColors.Warning,
                                    fontSize = 10.sp,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                description.orEmpty().ifBlank { "No description" },
                color = NostrordColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(36.dp),
            )
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                shape = NostrordShapes.shapeMedium,
                color = if (ctaPrimary) NostrordColors.Primary else NostrordColors.BackgroundFloating,
            ) {
                Text(
                    cta,
                    color = if (ctaPrimary) Color.White else NostrordColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
}

/** Dashed-border empty state with an emoji tile, title, description and action slot. */
@Composable
fun EmptyStateCard(
    emoji: String,
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    actions: (@Composable () -> Unit)? = null,
) {
    val borderColor = NostrordColors.Divider
    Column(
        modifier =
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(
                    color = borderColor,
                    style =
                    Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)),
                    ),
                    cornerRadius = CornerRadius(12.dp.toPx()),
                )
            }
            .padding(horizontal = 24.dp, vertical = 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier =
            Modifier
                .size(56.dp)
                .clip(NostrordShapes.shapeXLarge)
                .background(NostrordColors.BackgroundFloating),
            contentAlignment = Alignment.Center,
        ) {
            Text(emoji, fontSize = 28.sp)
        }
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            title,
            color = NostrordColors.TextPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            description,
            color = NostrordColors.TextMuted,
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 384.dp),
        )
        actions?.let {
            Spacer(modifier = Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { it() }
        }
    }
}
