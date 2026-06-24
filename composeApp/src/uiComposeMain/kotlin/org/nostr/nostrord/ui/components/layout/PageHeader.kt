package org.nostr.nostrord.ui.components.layout

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * The standard new-design page header, matching the home page on every screen: a 48dp bar
 * with the drawer hamburger ([FrameMenuButton], mobile only), a muted leading [icon], the
 * [title] (15sp SemiBold), an optional [trailing] slot (badge / action icons), then a
 * divider. Keeps font, side padding and the leading icon identical across pages.
 */
@Composable
fun PageHeader(
    icon: ImageVector,
    title: String,
    onOpenDrawer: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        onOpenDrawer?.let { open ->
            FrameMenuButton(onClick = open)
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = NostrordColors.TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            title,
            color = NostrordColors.TextPrimary,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        // The title stays its natural width (a Row constraint still ellipsizes a long one);
        // a caller's trailing Spacer(weight) is then the only weighted child, so it fills and
        // pushes the action icons to the far right instead of sharing the space with the title.
        trailing()
    }
    HorizontalDivider(color = NostrordColors.Divider)
}
