package org.nostr.nostrord.ui.components.buttons

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

/**
 * Button design system — the Compose counterpart of the web's `btn-*` component
 * classes (btn-primary / btn-secondary / btn-ghost / btn-danger / btn-success with
 * btn-sm / btn-lg / btn-full modifiers). One component, variant + size parameters,
 * hover and pointer cursor built in, so screens stop hand-rolling Button color
 * blocks and the two UIs keep the same vocabulary.
 */
enum class AppButtonVariant { Primary, Secondary, Ghost, Danger, Success }

enum class AppButtonSize { Small, Medium, Large }

@Composable
fun AppButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    size: AppButtonSize = AppButtonSize.Medium,
    fullWidth: Boolean = false,
    loading: Boolean = false,
    icon: ImageVector? = null,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    // Hover only counts when the button is actually interactive: a disabled button (Mute /
    // Report before they're wired) must not light up its background or content on hover.
    val hovered = isHovered && enabled && !loading

    // Variant colors mirror styles.css .btn-* and the prototype's Button component.
    val container: Color
    val containerHover: Color
    val content: Color
    when (variant) {
        AppButtonVariant.Primary -> {
            container = NostrordColors.Primary
            containerHover = NostrordColors.PrimaryVariant
            content = Color.White
        }
        AppButtonVariant.Secondary -> {
            container = NostrordColors.SurfaceVariant
            containerHover = NostrordColors.InputHover
            content = NostrordColors.TextContent
        }
        AppButtonVariant.Ghost -> {
            container = Color.Transparent
            containerHover = NostrordColors.HoverBackground
            content = if (hovered) NostrordColors.TextPrimary else NostrordColors.TextSecondary
        }
        AppButtonVariant.Danger -> {
            container = NostrordColors.Error
            containerHover = Color(0xFFC93B3E)
            content = Color.White
        }
        AppButtonVariant.Success -> {
            container = NostrordColors.Success
            containerHover = Color(0xFF46C46F)
            content = Color(0xFF0B1F12)
        }
    }

    val height =
        when (size) {
            AppButtonSize.Small -> 32.dp
            AppButtonSize.Medium -> 40.dp
            AppButtonSize.Large -> 48.dp
        }
    val fontSize =
        when (size) {
            AppButtonSize.Small -> 13.sp
            AppButtonSize.Medium -> 14.sp
            AppButtonSize.Large -> 15.sp
        }
    // Horizontal padding + icon size mirror the web .btn-* / .profile-btn (sm 12px/16px,
    // base 18px/18px). Vertical content padding is 0 so the label is never squeezed inside the
    // fixed height: the height centers the content with room to spare instead of clipping it.
    val horizontalPadding =
        when (size) {
            AppButtonSize.Small -> 12.dp
            AppButtonSize.Medium -> 18.dp
            AppButtonSize.Large -> 20.dp
        }
    val iconSize =
        when (size) {
            AppButtonSize.Small -> 16.dp
            AppButtonSize.Medium -> 18.dp
            AppButtonSize.Large -> 20.dp
        }
    val iconGap = if (size == AppButtonSize.Small) 6.dp else 8.dp

    Button(
        onClick = { if (enabled && !loading) onClick() },
        enabled = enabled && !loading,
        interactionSource = interactionSource,
        shape = NostrordShapes.buttonShape,
        colors =
        ButtonDefaults.buttonColors(
            containerColor = if (hovered) containerHover else container,
            contentColor = content,
            disabledContainerColor = container.copy(alpha = if (variant == AppButtonVariant.Ghost) 0f else 0.5f),
            disabledContentColor = content.copy(alpha = 0.7f),
        ),
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 0.dp),
        modifier =
        modifier
            .then(if (fullWidth) Modifier.fillMaxWidth() else Modifier)
            .height(height)
            .hoverable(interactionSource)
            .pointerHoverIcon(if (enabled && !loading) PointerIcon.Hand else PointerIcon.Default),
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = content,
                strokeWidth = 2.dp,
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(iconSize),
            )
            Spacer(modifier = Modifier.width(iconGap))
        }
        Text(text, fontSize = fontSize, fontWeight = FontWeight.SemiBold)
    }
}
