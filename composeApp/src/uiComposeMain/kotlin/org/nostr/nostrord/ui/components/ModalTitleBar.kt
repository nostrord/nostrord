package org.nostr.nostrord.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Modal title bar (prototype Modal header): title + close button over a 1px
 * bottom border. Mirrors the web `.modal-header` object inside zero-padding
 * cards (.info-card / .profile-card).
 */
@Composable
fun ModalTitleBar(
    title: String,
    onClose: () -> Unit,
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            color = NostrordColors.TextPrimary,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        IconButton(
            onClick = onClose,
            modifier =
            Modifier
                .size(28.dp)
                .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Close",
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    HorizontalDivider(color = NostrordColors.Divider, thickness = 1.dp)
}
