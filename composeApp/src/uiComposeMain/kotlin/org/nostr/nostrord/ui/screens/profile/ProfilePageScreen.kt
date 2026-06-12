package org.nostr.nostrord.ui.screens.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.IdentifierField
import org.nostr.nostrord.ui.components.avatars.OptimizedSmallAvatar
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.navigation.GroupRoute
import org.nostr.nostrord.ui.theme.AvatarGradients
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * New-design user profile page (prototype Profile, /u/:pubkey): header, identity
 * card (gradient banner matching the user's avatar identity, avatar, name + ADMIN
 * badge, NIP-05, about, npub with copy) and the groups this user is in. Follow /
 * DM actions arrive with the follow and DM features. Mirrors the web
 * web/screens/ProfilePage.
 */
@Composable
fun ProfilePageScreen(
    pubkey: String,
    onOpenGroup: (GroupRoute) -> Unit,
    onEditProfile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val vm = viewModel(key = "profile-$pubkey") { ProfilePageViewModel(AppModule.nostrRepository, pubkey) }
    val metadata by vm.metadata.collectAsState()
    val groups by vm.groupsWithUser.collectAsState()
    val isAdminSomewhere by vm.isAdminSomewhere.collectAsState()

    val name =
        metadata?.displayName?.takeIf { it.isNotBlank() }
            ?: metadata?.name?.takeIf { it.isNotBlank() }
            ?: vm.npub.take(12) + "..."

    Column(modifier = modifier.fillMaxSize().background(NostrordColors.Background)) {
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Profile",
                color = NostrordColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        HorizontalDivider(color = NostrordColors.Divider)

        Column(
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(modifier = Modifier.widthIn(max = 672.dp).padding(Spacing.xl)) {
                // Identity card: banner + avatar + name + about + npub.
                Surface(shape = NostrordShapes.shapeXLarge, color = NostrordColors.Surface) {
                    Column {
                        ProfileBanner(seed = pubkey)
                        Column(modifier = Modifier.padding(horizontal = Spacing.xl).padding(bottom = Spacing.xl)) {
                            Row(
                                modifier = Modifier.fillMaxWidth().offset(y = (-40).dp),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Box(
                                    modifier =
                                    Modifier
                                        .size(88.dp)
                                        .clip(CircleShape)
                                        .background(NostrordColors.Surface),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    OptimizedSmallAvatar(
                                        imageUrl = metadata?.picture,
                                        identifier = pubkey,
                                        displayName = name,
                                        size = 80.dp,
                                        shape = CircleShape,
                                    )
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                if (vm.isSelf) {
                                    AppButton(
                                        text = "Edit profile",
                                        onClick = onEditProfile,
                                        variant = AppButtonVariant.Secondary,
                                    )
                                }
                            }

                            Column(modifier = Modifier.offset(y = (-28).dp)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                                ) {
                                    Text(
                                        name,
                                        color = NostrordColors.TextPrimary,
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (isAdminSomewhere) AdminBadge()
                                }
                                metadata?.nip05?.takeIf { it.isNotBlank() }?.let {
                                    Text(it, color = NostrordColors.Success, fontSize = 14.sp)
                                }
                                metadata?.about?.takeIf { it.isNotBlank() }?.let {
                                    Spacer(modifier = Modifier.height(Spacing.md))
                                    Text(it, color = NostrordColors.TextSecondary, fontSize = 15.sp, lineHeight = 21.sp)
                                }
                                Spacer(modifier = Modifier.height(Spacing.lg))
                                // Cycling identifier (prototype IdentifierField): npub /
                                // nprofile / link / hex / nip-05 with swap + copy.
                                IdentifierField(pubkey = pubkey, nip05 = metadata?.nip05)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xxl))
                Text(
                    (if (vm.isSelf) "YOUR GROUPS" else "GROUPS IN COMMON") + " · ${groups.size}",
                    color = NostrordColors.TextMuted,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 0.5.sp,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                if (groups.isEmpty()) {
                    Text(
                        "No groups in common.",
                        color = NostrordColors.TextMuted,
                        fontSize = 13.sp,
                        modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xxl),
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        groups.forEach { group ->
                            ProfileGroupRow(group = group, onClick = { onOpenGroup(GroupRoute(group.relayUrl, group.meta.id)) })
                        }
                    }
                }
            }
        }
    }
}

/** Banner from the user's avatar identity hues, darkened (prototype: u.color into dark). */
@Composable
private fun ProfileBanner(seed: String) {
    val gradient = remember(seed) { AvatarGradients.user(seed) }
    val dark = NostrordColors.BackgroundDark
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(112.dp)
            .drawBehind {
                drawRect(
                    brush =
                    Brush.linearGradient(
                        colors =
                        listOf(
                            Color.hsl(gradient.start.hue.toFloat(), 0.62f, 0.40f),
                            dark,
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height),
                    ),
                )
            },
    )
}

@Composable
private fun AdminBadge() {
    Surface(
        shape = NostrordShapes.shapeSmall,
        color = NostrordColors.Primary.copy(alpha = 0.2f),
    ) {
        Text(
            "ADMIN",
            color = NostrordColors.Primary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ProfileGroupRow(
    group: ProfileGroup,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Surface(
        modifier = Modifier.fillMaxWidth().clip(NostrordShapes.shapeMedium).hoverable(interactionSource),
        shape = NostrordShapes.shapeMedium,
        color = if (isHovered) NostrordColors.HoverBackground else NostrordColors.Surface,
        onClick = onClick,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        ) {
            OptimizedSmallAvatar(
                imageUrl = group.meta.picture,
                identifier = group.meta.id,
                displayName = group.meta.name ?: group.meta.id,
                size = 36.dp,
                shape = NostrordShapes.shapeMedium,
                isGroup = true,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    group.meta.name ?: group.meta.id,
                    color = NostrordColors.TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (group.memberCount > 0) {
                    Text(
                        "${group.memberCount} members",
                        color = NostrordColors.TextMuted,
                        fontSize = 12.sp,
                    )
                }
            }
            if (group.isAdmin) {
                Surface(shape = NostrordShapes.shapeSmall, color = NostrordColors.Primary.copy(alpha = 0.2f)) {
                    Text(
                        "admin",
                        color = NostrordColors.Primary,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}
