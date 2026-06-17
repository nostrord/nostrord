package org.nostr.nostrord.ui.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

/**
 * Tone-tinted access pill (Public/Private, Open/Closed, Joined). 10sp semibold,
 * background a 15% tint of [color]. The single reusable badge primitive for the
 * NIP-29 group tags; do not clone per screen.
 */
@Composable
fun TagBadge(
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

/**
 * The two NIP-29 access pills (kind:39000) with canonical tones, matching the web
 * `groupTypeBadges`: Public green / Private yellow, Open purple / Closed orange.
 * Place inside a Row; the pills are separated by a 6.dp internal gap.
 */
@Composable
fun GroupTypeBadges(
    isPublic: Boolean,
    isOpen: Boolean,
) {
    TagBadge(
        text = if (isPublic) "Public" else "Private",
        color = if (isPublic) NostrordColors.Success else NostrordColors.Warning,
    )
    Spacer(modifier = Modifier.width(6.dp))
    TagBadge(
        text = if (isOpen) "Open" else "Closed",
        color = if (isOpen) NostrordColors.Primary else NostrordColors.WarningOrange,
    )
}
