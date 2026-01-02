package org.nostr.nostrord.ui.components.navigation

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.outlined.Explore
import androidx.compose.material.icons.outlined.Groups
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.Spacing

/**
 * Navigation items for the bottom bar.
 *
 * Mobile-first layout:
 * - Explore: Discover and browse groups
 * - Groups: Your joined groups list
 * - Notifications: Activity alerts (future)
 * - Profile: User settings and info
 */
enum class BottomNavItem(
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val enabled: Boolean = true
) {
    Explore(
        label = "Explore",
        selectedIcon = Icons.Filled.Explore,
        unselectedIcon = Icons.Outlined.Explore
    ),
    Groups(
        label = "Groups",
        selectedIcon = Icons.Filled.Groups,
        unselectedIcon = Icons.Outlined.Groups
    ),
    Notifications(
        label = "Alerts",
        selectedIcon = Icons.Filled.Notifications,
        unselectedIcon = Icons.Outlined.Notifications,
        enabled = false // Future feature
    ),
    Profile(
        label = "You",
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person
    )
}

/**
 * Bottom navigation bar for mobile screens.
 * Provides navigation between Explore, Groups, Notifications, and Profile.
 *
 * Touch targets are 48dp minimum for accessibility.
 */
@Composable
fun BottomNavigationBar(
    selectedItem: BottomNavItem,
    onItemSelected: (BottomNavItem) -> Unit,
    modifier: Modifier = Modifier,
    hasUnreadNotifications: Boolean = false,
    unreadGroupCount: Int = 0,
    userAvatarUrl: String? = null,
    userDisplayName: String? = null,
    userPubkey: String? = null
) {
    NavigationBar(
        modifier = modifier,
        containerColor = NostrordColors.BackgroundDark,
        contentColor = Color.White,
        tonalElevation = 0.dp
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
                            when {
                                // Show notification badge for Notifications item
                                item == BottomNavItem.Notifications && hasUnreadNotifications -> {
                                    Badge(containerColor = NostrordColors.Error)
                                }
                                // Show unread count badge for Groups item
                                item == BottomNavItem.Groups && unreadGroupCount > 0 -> {
                                    Badge(
                                        containerColor = NostrordColors.Error,
                                        contentColor = Color.White
                                    ) {
                                        Text(
                                            text = if (unreadGroupCount > 99) "99+" else unreadGroupCount.toString(),
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                }
                            }
                        }
                    ) {
                        if (item == BottomNavItem.Profile && userPubkey != null) {
                            // Show user avatar for Profile tab
                            ProfileAvatar(
                                imageUrl = userAvatarUrl,
                                displayName = userDisplayName ?: "Profile",
                                pubkey = userPubkey,
                                size = Spacing.iconMd,
                                modifier = if (isSelected) {
                                    Modifier
                                        .clip(CircleShape)
                                        .border(2.dp, NostrordColors.Primary, CircleShape)
                                } else {
                                    Modifier
                                }
                            )
                        } else {
                            Icon(
                                imageVector = if (isSelected) item.selectedIcon else item.unselectedIcon,
                                contentDescription = item.label,
                                modifier = Modifier.size(Spacing.iconMd)
                            )
                        }
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
