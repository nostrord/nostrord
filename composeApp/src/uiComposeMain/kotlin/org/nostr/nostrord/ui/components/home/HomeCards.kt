package org.nostr.nostrord.ui.components.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
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
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.loading.shimmerEffect
import org.nostr.nostrord.ui.screens.home.Friend
import org.nostr.nostrord.ui.screens.home.RelayHeaderIcon
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
    modifier: Modifier = Modifier,
    cta: String? = null,
    ctaPrimary: Boolean = false,
    people: List<Friend> = emptyList(),
    peopleLoading: Boolean = false,
    isPublic: Boolean = true,
    isOpen: Boolean = true,
    hasMetadata: Boolean = false,
    relayUrl: String = "",
    relayIconUrl: String? = null,
    onClick: () -> Unit = {},
) {
    Surface(
        modifier = modifier.fillMaxWidth().fillMaxHeight().clip(NostrordShapes.shapeLarge),
        shape = NostrordShapes.shapeLarge,
        color = NostrordColors.Surface,
        onClick = onClick,
    ) {
        Column(modifier = Modifier.fillMaxHeight().padding(16.dp)) {
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
                    // People row directly under the name: overlapping friend/member
                    // avatars (when known) and the total "N people" count, with the
                    // restricted badge riding along.
                    val peopleCount = if (people.isNotEmpty()) maxOf(memberCount, people.size) else memberCount
                    // peopleLoading is owned by the VM: it stays true only while the
                    // member list is in flight, and flips off (skeleton stops) once the
                    // list arrives or the fetch times out.
                    // No one to preview (and not loading): fall back to the group's
                    // access-tag badges so the row carries info instead of sitting empty.
                    val showTags = people.isEmpty() && !peopleLoading && hasMetadata
                    if (peopleCount > 0 || restricted || peopleLoading || showTags) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            when {
                                people.isNotEmpty() -> {
                                    AvatarStack(people)
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                peopleLoading -> {
                                    Row(horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
                                        repeat(4) {
                                            Box(
                                                modifier =
                                                Modifier
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .shimmerEffect(),
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                showTags -> {
                                    TagBadge(
                                        text = if (isPublic) "Public" else "Private",
                                        color = if (isPublic) NostrordColors.Success else NostrordColors.Warning,
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    TagBadge(
                                        text = if (isOpen) "Open" else "Closed",
                                        color = if (isOpen) NostrordColors.Primary else NostrordColors.WarningOrange,
                                    )
                                    if (peopleCount > 0 || restricted) Spacer(modifier = Modifier.width(6.dp))
                                }
                            }
                            if (!showTags && peopleCount > 0) {
                                Text(
                                    "$peopleCount people",
                                    color = NostrordColors.TextMuted,
                                    fontSize = 12.sp,
                                )
                            } else if (peopleLoading) {
                                Box(
                                    modifier =
                                    Modifier
                                        .width(56.dp)
                                        .height(12.dp)
                                        .clip(NostrordShapes.shapeSmall)
                                        .shimmerEffect(),
                                )
                            }
                            if (restricted) {
                                if (peopleCount > 0 && !showTags) Spacer(modifier = Modifier.width(6.dp))
                                TagBadge(text = "Restricted", color = NostrordColors.Error)
                            }
                        }
                    }
                }
            }
            // Single-line description, always shown below the head (the agreed card shape).
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                description.orEmpty().ifBlank { "No description" },
                color = NostrordColors.TextSecondary,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.height(18.dp),
            )
            // Host relay on its own muted line: a small relay icon + the short hostname, so
            // the same group on two relays is told apart.
            if (relayUrl.isNotBlank()) {
                val relayHost = relayUrl.removePrefix("wss://").removePrefix("ws://").trimEnd('/')
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RelayHeaderIcon(relayUrl = relayUrl, iconUrl = relayIconUrl, label = relayHost, size = 16.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        relayHost,
                        color = NostrordColors.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (cta != null) {
                // Push the CTA to the bottom so equal-height cards line their chips up.
                Spacer(modifier = Modifier.weight(1f).heightIn(min = 12.dp))
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
}

/** Access-tag pill (Public/Private, Open/Closed) shown when a card has no people. */
@Composable
private fun TagBadge(
    text: String,
    color: Color,
) {
    Surface(
        shape = NostrordShapes.shapeSmall,
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text,
            color = color,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** A person's display name for a discovery card: profile name, then a short npub. */
private fun personName(friend: Friend): String = friend.metadata?.displayName?.takeIf { it.isNotBlank() }
    ?: friend.metadata?.name?.takeIf { it.isNotBlank() }
    ?: (Nip19.encodeNpub(friend.pubkey).take(10) + "…")

/** Overlapping avatar row (prototype `-space-x-2`), capped at [max] previews. */
@Composable
private fun AvatarStack(
    people: List<Friend>,
    max: Int = 5,
) {
    val shown = people.take(max)
    if (shown.isEmpty()) return
    val size = 20.dp
    val overlap = 6.dp
    Box(modifier = Modifier.height(size).width(size + (size - overlap) * (shown.size - 1))) {
        shown.forEachIndexed { index, friend ->
            Box(
                modifier =
                Modifier
                    .offset(x = (size - overlap) * index)
                    .size(size)
                    .clip(CircleShape)
                    .border(1.5.dp, NostrordColors.Surface, CircleShape),
            ) {
                OptimizedSmallAvatar(
                    imageUrl = friend.metadata?.picture,
                    identifier = friend.pubkey,
                    displayName = personName(friend),
                    size = size,
                    shape = CircleShape,
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
