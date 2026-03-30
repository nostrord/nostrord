package org.nostr.nostrord.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.generateColorFromString

private val cardShape = RoundedCornerShape(8.dp)
private val iconShape = RoundedCornerShape(8.dp)
private val badgeShape = RoundedCornerShape(10.dp)
private val btnShape = RoundedCornerShape(4.dp)

@Composable
fun PickGroupCard(
    group: GroupMetadata,
    isJoined: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val cardBg = if (isHovered) NostrordColors.Surface else NostrordColors.BackgroundDark
    val borderColor = if (isHovered) NostrordColors.SurfaceVariant else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(cardShape)
            .background(cardBg)
            .border(1.dp, borderColor, cardShape)
            .hoverable(interactionSource)
            .clickable(onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top
    ) {
        // 44×44 rounded square icon
        GroupPickIcon(group = group, size = 44.dp)

        Spacer(modifier = Modifier.width(14.dp))

        // Info column
        Column(modifier = Modifier.weight(1f)) {
            val displayName = group.name ?: group.id
            Text(
                text = displayName,
                color = NostrordColors.TextPrimary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            val about = group.about?.takeIf { it.isNotBlank() }
            if (about != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = about,
                    color = NostrordColors.TextMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    lineHeight = 17.sp
                )
            }

            Spacer(modifier = Modifier.height(7.dp))

            // Badge row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (!group.isPublic) {
                    PickBadge(
                        text = "private",
                        textColor = NostrordColors.Pink,
                        bgColor = NostrordColors.Pink.copy(alpha = 0.15f)
                    )
                }
                if (!group.isOpen) {
                    PickBadge(
                        text = "invite only",
                        textColor = NostrordColors.StatusIdle,
                        bgColor = NostrordColors.StatusIdle.copy(alpha = 0.15f)
                    )
                } else {
                    PickBadge(
                        text = "open",
                        textColor = NostrordColors.Success,
                        bgColor = NostrordColors.Success.copy(alpha = 0.15f)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Join / Joined / Request button
        val btnLabel = when {
            isJoined -> "Joined"
            !group.isOpen -> "Request"
            else -> "Join"
        }
        val btnBg = when {
            isJoined -> NostrordColors.Success
            else -> NostrordColors.Primary
        }

        Box(
            modifier = Modifier
                .clip(btnShape)
                .background(btnBg)
                .then(if (!isJoined) Modifier.clickable(onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier)
                .padding(horizontal = 16.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = btnLabel,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun GroupPickIcon(group: GroupMetadata, size: androidx.compose.ui.unit.Dp) {
    val context = LocalPlatformContext.current
    val pictureUrl = group.picture
    var imageState by remember(pictureUrl) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    val showImage = !pictureUrl.isNullOrBlank() && imageState !is AsyncImagePainter.State.Error
    val displayName = group.name ?: group.id

    Box(
        modifier = Modifier
            .size(size)
            .clip(iconShape)
            .background(if (!showImage) generateColorFromString(group.id) else NostrordColors.BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        if (!showImage) {
            Text(
                text = displayName.take(1).uppercase(),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (!pictureUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(pictureUrl)
                    .crossfade(true)
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = displayName,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(iconShape),
                contentScale = ContentScale.Crop,
                onState = { imageState = it }
            )
        }
    }
}

@Composable
private fun PickBadge(text: String, textColor: Color, bgColor: Color) {
    Box(
        modifier = Modifier
            .clip(badgeShape)
            .background(bgColor)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.01.sp
        )
    }
}
