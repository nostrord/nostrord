@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package org.nostr.nostrord.ui.screens.group.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

@Composable
internal fun EditFieldLabel(text: String) {
    Text(
        text = text,
        style = NostrordTypography.Caption,
        color = NostrordColors.TextSecondary,
        fontWeight = FontWeight.Medium,
    )
}

@Composable
internal fun EditAccessToggleRow(
    icon: ImageVector,
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column {
                Text(
                    text = label,
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextPrimary,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = description,
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextMuted,
                )
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors =
            SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = NostrordColors.Primary,
                uncheckedThumbColor = NostrordColors.TextMuted,
                uncheckedTrackColor = NostrordColors.InputBackground,
            ),
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        )
    }
}

@Composable
internal fun ChildClaimRow(
    groupId: String,
    groupName: String?,
    isApproved: Boolean,
    onActionClick: () -> Unit,
    actionLabel: String? = null,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(NostrordColors.SurfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = groupName?.takeIf { it.isNotBlank() } ?: groupId,
                style = NostrordTypography.Caption,
                color = NostrordColors.TextPrimary,
                fontWeight = FontWeight.Medium,
            )
            if (!groupName.isNullOrBlank()) {
                Text(
                    text = groupId,
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextMuted,
                )
            }
        }
        Spacer(modifier = Modifier.width(Spacing.sm))
        TextButton(
            onClick = onActionClick,
            modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                imageVector = if (isApproved) Icons.Default.Close else Icons.Default.Check,
                contentDescription = null,
                tint = if (isApproved) NostrordColors.Error else NostrordColors.Primary,
                modifier = Modifier.size(16.dp),
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
            Text(
                text = actionLabel ?: if (isApproved) "Remove" else "Approve",
                color = if (isApproved) NostrordColors.Error else NostrordColors.Primary,
                style = NostrordTypography.Caption,
            )
        }
    }
}

@Composable
internal fun editFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = NostrordColors.TextPrimary,
    unfocusedTextColor = NostrordColors.TextPrimary,
    focusedBorderColor = NostrordColors.Primary,
    unfocusedBorderColor = NostrordColors.Divider,
    cursorColor = NostrordColors.Primary,
    focusedContainerColor = NostrordColors.InputBackground,
    unfocusedContainerColor = NostrordColors.InputBackground,
    focusedPlaceholderColor = NostrordColors.TextMuted,
    unfocusedPlaceholderColor = NostrordColors.TextMuted,
    focusedTrailingIconColor = NostrordColors.TextSecondary,
    unfocusedTrailingIconColor = NostrordColors.TextMuted,
)

/** Input text style for the group edit forms: 14sp to match the web .modal-input (16sp default felt oversized). */
@Composable
internal fun editFieldTextStyle() = LocalTextStyle.current.copy(fontSize = 14.sp)
