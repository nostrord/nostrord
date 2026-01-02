package org.nostr.nostrord.ui.components.buttons

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Button(
        onClick = { if (enabled) onClick() },
        enabled = enabled,
        interactionSource = interactionSource,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isHovered) NostrordColors.PrimaryVariant else NostrordColors.Primary,
            contentColor = Color.White,
            disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.4f),
            disabledContentColor = NostrordColors.TextSecondary
        ),
        modifier = modifier
            .hoverable(interactionSource)
            .pointerHoverIcon(
                if (enabled) PointerIcon.Hand else PointerIcon.Default
            )
    ) {
        Text(
            text = text,
            style = NostrordTypography.Button,
            modifier = Modifier.padding(
                horizontal = Spacing.buttonSmallPaddingH,
                vertical = Spacing.buttonSmallPaddingV
            )
        )
    }
}
