package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Add-group chooser (prototype GroupModals 'chooser', opened by the rail "+"): pick
 * between joining an existing group or creating a new one. Mirrors the web
 * modals/AddGroupModal.
 */
@Composable
fun AddGroupModal(
    onJoin: () -> Unit,
    onCreate: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = NostrordShapes.shapeXLarge,
            color = NostrordColors.Surface,
        ) {
            Column(modifier = Modifier.width(420.dp).padding(Spacing.xxl)) {
                Text(
                    "Add group",
                    color = NostrordColors.TextPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    "How do you want to start?",
                    color = NostrordColors.TextSecondary,
                    fontSize = 14.sp,
                )
                Spacer(modifier = Modifier.height(Spacing.lg))
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                    ChoiceCard(
                        icon = Icons.Default.Link,
                        title = "Join a group",
                        description = "Use an invite link, naddr, or ID to join a group that already exists.",
                        onClick = onJoin,
                    )
                    ChoiceCard(
                        icon = Icons.Default.People,
                        title = "Create a group",
                        description = "Create a moderated group from scratch, hosted on a groups relay.",
                        onClick = onCreate,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    Surface(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(NostrordShapes.shapeXLarge)
            .hoverable(interactionSource),
        shape = NostrordShapes.shapeXLarge,
        color = if (isHovered) NostrordColors.HoverBackground else NostrordColors.Surface,
        border = BorderStroke(1.dp, if (isHovered) NostrordColors.Primary else NostrordColors.Divider),
        onClick = onClick,
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xxl, vertical = Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            Box(
                modifier =
                Modifier
                    .size(56.dp)
                    .clip(NostrordShapes.shapeXLarge)
                    .background(NostrordColors.Primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = NostrordColors.Primary,
                    modifier = Modifier.size(28.dp),
                )
            }
            Text(
                title,
                color = NostrordColors.TextPrimary,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                description,
                color = NostrordColors.TextSecondary,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
