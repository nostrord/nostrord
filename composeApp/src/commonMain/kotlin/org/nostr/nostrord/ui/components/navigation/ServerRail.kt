package org.nostr.nostrord.ui.components.navigation

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.input.pointer.pointerHoverIcon
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.compose.LocalPlatformContext
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Size as CoilSize
import org.nostr.nostrord.utils.getImageUrl
import org.nostr.nostrord.nostr.Nip11RelayInfo
import org.nostr.nostrord.nostr.isValidIconUrl
import org.nostr.nostrord.ui.components.avatars.OptimizedUserAvatar
import org.nostr.nostrord.ui.theme.NostrordAnimation
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing
import org.nostr.nostrord.ui.util.relayFallbackPainter

/**
 * Relay Rail — leftmost 72dp column.
 *
 * Shows one icon per NIP-29 relay the user has connected to.
 * Clicking a relay calls switchRelay, which updates the groups sidebar (column 2).
 */
@Composable
fun ServerRail(
    relays: List<String>,
    activeRelayUrl: String,
    onRelayClick: (String) -> Unit,
    onAddRelayClick: () -> Unit,
    modifier: Modifier = Modifier,
    relayMetadata: Map<String, Nip11RelayInfo> = emptyMap(),
    userAvatarUrl: String? = null,
    userDisplayName: String? = null,
    userPubkey: String? = null,
    onUserClick: () -> Unit = {},
    isProfileActive: Boolean = false
) {
    val listState = rememberLazyListState()

    Column(
        modifier = Modifier
            .fillMaxHeight()
            .width(Spacing.serverRailWidth)
            .background(NostrordColors.BackgroundDark)
            .padding(vertical = Spacing.sm)
            .then(modifier),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            items(relays, key = { it }) { relayUrl ->
                val isActive = relayUrl == activeRelayUrl
                val meta = relayMetadata[relayUrl]
                val tooltipText = meta?.name?.takeIf { it.isNotBlank() } ?: relayUrl
                ServerRailItem(
                    isActive = isActive,
                    onClick = { onRelayClick(relayUrl) },
                    tooltip = tooltipText
                ) {
                    RelayIcon(relayUrl = relayUrl, isActive = isActive, iconUrl = meta?.icon)
                }
            }

            // Add relay button
            item(key = "add") {
                Spacer(modifier = Modifier.height(Spacing.xs))
                ServerRailItem(
                    isActive = false,
                    onClick = onAddRelayClick,
                    showIndicator = false,
                    tooltip = "Add relay"
                ) {
                    Box(
                        modifier = Modifier
                            .size(Spacing.serverIconSize)
                            .background(
                                NostrordColors.SurfaceVariant,
                                RoundedCornerShape(NostrordShapes.serverIconDefault)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Add relay",
                            tint = NostrordColors.Success,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
        }

        // User avatar at bottom
        Spacer(modifier = Modifier.height(Spacing.sm))
        UserAvatar(
            avatarUrl = userAvatarUrl,
            displayName = userDisplayName,
            pubkey = userPubkey,
            onClick = onUserClick,
            tooltip = userDisplayName ?: "Profile",
            isActive = isProfileActive
        )
    }
}

/** Extracts a short readable label from a relay URL. */
fun relayShortLabel(url: String): String {
    val domain = url.removePrefix("wss://").removePrefix("ws://").split("/").first()
    val parts = domain.split(".")
    val label = parts.filter { it !in setOf("groups", "relay", "relay1", "relay2", "www", "nostr", "com", "io", "net", "org") }
        .firstOrNull() ?: parts.firstOrNull() ?: domain
    return label.take(7)
}

/**
 * Relay icon — shows NIP-11 relay icon if available, otherwise abbreviated domain name.
 * Morphs corner radius on hover/active.
 *
 * Pattern: text label always rendered as base layer; image overlaid on top when loaded.
 * If the image fails or is absent, the text shows through.
 */
@Composable
private fun RelayIcon(relayUrl: String, isActive: Boolean, iconUrl: String? = null) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val cornerRadius by animateDpAsState(
        targetValue = when {
            isActive || isHovered -> NostrordShapes.serverIconActive
            else -> NostrordShapes.serverIconDefault
        },
        animationSpec = NostrordAnimation.standardSpec()
    )

    val fallbackPainter = if (iconUrl.isNullOrBlank()) relayFallbackPainter(relayUrl) else null
    val hasIcon = isValidIconUrl(iconUrl)

    // retryCount drives recomposition: each increment resets imageState and rebuilds the request.
    var retryCount by remember(iconUrl) { mutableIntStateOf(0) }
    var imageState by remember(iconUrl, retryCount) {
        mutableStateOf<AsyncImagePainter.State>(AsyncImagePainter.State.Empty)
    }
    LaunchedEffect(imageState) {
        if (imageState is AsyncImagePainter.State.Error && retryCount < 2) {
            println("[RelayIcon] load failed for $iconUrl (attempt ${retryCount + 1}/3), retrying in 3s")
            delay(3_000)
            retryCount++
        }
    }

    val showImage = (fallbackPainter != null) || (hasIcon && imageState !is AsyncImagePainter.State.Error)
    val showText = !showImage

    // clip() before background() so children are clipped to the rounded shape too
    Box(
        modifier = Modifier
            .size(Spacing.serverIconSize)
            .hoverable(interactionSource)
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                color = when {
                    isActive -> NostrordColors.Primary
                    isHovered -> NostrordColors.Primary.copy(alpha = 0.7f)
                    else -> NostrordColors.SurfaceVariant
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (showText) {
            Text(
                text = relayShortLabel(relayUrl),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Clip,
                modifier = Modifier.padding(horizontal = 2.dp)
            )
        }

        if (fallbackPainter != null) {
            androidx.compose.foundation.Image(
                painter = fallbackPainter,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else if (hasIcon) {
            val context = LocalPlatformContext.current
            val density = LocalDensity.current
            val sizeInPx = with(density) { Spacing.serverIconSize.roundToPx() }
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(getImageUrl(iconUrl!!))
                    .crossfade(true)
                    .size(CoilSize(sizeInPx, sizeInPx))
                    .memoryCachePolicy(CachePolicy.ENABLED)
                    .diskCachePolicy(CachePolicy.ENABLED)
                    .build(),
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                filterQuality = FilterQuality.High,
                modifier = Modifier.fillMaxSize(),
                onState = { state ->
                    imageState = state
                    if (state is AsyncImagePainter.State.Error) {
                        println("[RelayIcon] Coil error loading $iconUrl (attempt ${retryCount + 1}): ${state.result.throwable?.message}")
                    } else if (state is AsyncImagePainter.State.Success) {
                        println("[RelayIcon] Coil success for $iconUrl")
                    }
                }
            )
        }
    }
}

/**
 * Individual item in the server rail with animated left-pill indicator.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerRailItem(
    isActive: Boolean,
    onClick: () -> Unit,
    showIndicator: Boolean = true,
    tooltip: String? = null,
    content: @Composable () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val indicatorHeight by animateDpAsState(
        targetValue = when {
            isActive -> Spacing.activeIndicatorHeight
            isHovered -> Spacing.hoverIndicatorHeight
            else -> 0.dp
        },
        animationSpec = NostrordAnimation.indicatorSpec()
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(Spacing.serverIconSize + Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (showIndicator) {
            Box(
                modifier = Modifier
                    .width(Spacing.activeIndicatorWidth)
                    .height(indicatorHeight)
                    .background(
                        if (indicatorHeight > 0.dp) Color.White else Color.Transparent,
                        RoundedCornerShape(
                            topEnd = Spacing.activeIndicatorWidth,
                            bottomEnd = Spacing.activeIndicatorWidth
                        )
                    )
            )
        } else {
            Spacer(modifier = Modifier.width(Spacing.activeIndicatorWidth))
        }

        Spacer(modifier = Modifier.weight(1f))

        val iconContent: @Composable () -> Unit = {
            Box(
                modifier = Modifier
                    .size(Spacing.serverIconSize)
                    .hoverable(interactionSource)
                    .clickable(onClick = onClick)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) {
                content()
            }
        }

        if (tooltip != null) {
            TooltipBox(
                positionProvider = RightSideTooltipPositionProvider(),
                tooltip = {
                    PlainTooltip(
                        containerColor = NostrordColors.Surface,
                        contentColor = NostrordColors.TextPrimary
                    ) {
                        Text(tooltip)
                    }
                },
                state = rememberTooltipState()
            ) {
                iconContent()
            }
        } else {
            iconContent()
        }

        Spacer(modifier = Modifier.weight(1f))
    }
}

/**
 * User avatar at the bottom of the relay rail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UserAvatar(
    avatarUrl: String?,
    displayName: String?,
    pubkey: String?,
    onClick: () -> Unit,
    tooltip: String = "Profile",
    isActive: Boolean = false
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    val indicatorHeight by animateDpAsState(
        targetValue = when {
            isActive -> Spacing.activeIndicatorHeight
            isHovered -> Spacing.hoverIndicatorHeight
            else -> 0.dp
        },
        animationSpec = NostrordAnimation.indicatorSpec()
    )

    val avatarContent: @Composable () -> Unit = {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(Spacing.serverIconSize + Spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(Spacing.activeIndicatorWidth)
                    .height(indicatorHeight)
                    .background(
                        if (indicatorHeight > 0.dp) Color.White else Color.Transparent,
                        RoundedCornerShape(
                            topEnd = Spacing.activeIndicatorWidth,
                            bottomEnd = Spacing.activeIndicatorWidth
                        )
                    )
            )

            Spacer(modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .size(Spacing.serverIconSize)
                    .hoverable(interactionSource)
                    .clickable(onClick = onClick)
                    .pointerHoverIcon(PointerIcon.Hand),
                contentAlignment = Alignment.Center
            ) {
                OptimizedUserAvatar(
                    imageUrl = avatarUrl,
                    pubkey = pubkey ?: "unknown",
                    displayName = displayName,
                    size = Spacing.serverIconSize
                )
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }

    TooltipBox(
        positionProvider = RightSideTooltipPositionProvider(),
        tooltip = {
            PlainTooltip(
                containerColor = NostrordColors.Surface,
                contentColor = NostrordColors.TextPrimary
            ) {
                Text(tooltip)
            }
        },
        state = rememberTooltipState()
    ) {
        avatarContent()
    }
}

private class RightSideTooltipPositionProvider(
    private val spacing: Int = 8
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize
    ): IntOffset {
        val x = anchorBounds.right + spacing
        val y = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
        return IntOffset(x, y)
    }
}
