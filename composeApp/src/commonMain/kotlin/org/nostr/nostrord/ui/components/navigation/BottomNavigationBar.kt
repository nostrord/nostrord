package org.nostr.nostrord.ui.components.navigation

import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Navigation items for the bottom bar.
 */
enum class BottomNavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val enabled: Boolean = true
) {
    Home(
        label = "Home",
        selectedIcon = Icons.Filled.Home,
        unselectedIcon = Icons.Outlined.Home
    ),
    Messages(
        label = "Messages",
        selectedIcon = Icons.Outlined.ChatBubbleOutline,
        unselectedIcon = Icons.Outlined.ChatBubbleOutline
    ),
    Notifications(
        label = "Alerts",
        selectedIcon = Icons.Filled.Notifications,
        unselectedIcon = Icons.Outlined.Notifications,
        enabled = false // Future feature
    ),
    Profile(
        label = "Profile",
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person
    )
}

/**
 * Bottom navigation bar for mobile screens.
 * Provides navigation between Home, Messages, Notifications, and Profile.
 */
@Composable
fun BottomNavigationBar(
    selectedItem: BottomNavItem,
    onItemSelected: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier,
    hasUnreadNotifications: Boolean = false
) {
    NavigationBar(
        modifier = modifier,
        containerColor = NostrordColors.BackgroundDark,
        contentColor = Color.White
    ) {
        BottomNavItem.entries.forEach { item ->
            val isSelected = selectedItem == item

            NavigationBarItem(
                selected = isSelected,
                onClick = {
                    if (item.enabled) {
                        onItemSelected(item)
                    }
                },
                icon = {
                    BadgedBox(
                        badge = {
                            // Show notification badge for Notifications item
                            if (item == BottomNavItem.Notifications && hasUnreadNotifications) {
                                Badge(
                                    containerColor = NostrordColors.Error
                                )
                            }
                        }
                    ) {
                        Icon(
                            imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                            contentDescription = item.label,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                label = {
                    Text(
                        text = item.label,
                        style = MaterialTheme.typography.labelSmall
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = NostrordColors.Primary,
                    selectedTextColor = NostrordColors.Primary,
                    unselectedIconColor = if (item.enabled) NostrordColors.TextSecondary else NostrordColors.TextMuted,
                    unselectedTextColor = if (item.enabled) NostrordColors.TextSecondary else NostrordColors.TextMuted,
                    indicatorColor = NostrordColors.Primary.copy(alpha = 0.1f)
                ),
                enabled = item.enabled
            )
        }
    }
}
