package org.nostr.nostrord.ui.components.sidebars

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.SwitchAccount
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.ui.components.avatars.ProfileAvatar
import org.nostr.nostrord.ui.components.badges.UnreadBadge
import org.nostr.nostrord.ui.components.scrollbar.VerticalScrollbarWrapper
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.util.generateColorFromString

@Composable
fun Sidebar(
    onNavigate: (Screen) -> Unit,
    connectionStatus: String,
    pubKey: String?,
    joinedGroups: Set<String>,
    groups: List<GroupMetadata>,
    unreadCounts: Map<String, Int> = emptyMap()
) {
    val scope = rememberCoroutineScope()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val currentUserMetadata: UserMetadata? = pubKey?.let { userMetadata[it] }

    // Request user metadata if not loaded
    LaunchedEffect(pubKey) {
        if (pubKey != null && !userMetadata.containsKey(pubKey)) {
            AppModule.nostrRepository.requestUserMetadata(setOf(pubKey))
        }
    }

    Column(
        modifier = Modifier
            .widthIn(min = 80.dp, max = 250.dp)
            .fillMaxHeight()
            .background(NostrordColors.Surface)
            .padding(8.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Nostrord",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(8.dp)
            )

            IconButton(onClick = { onNavigate(Screen.BackupPrivateKey) }) {
                Icon(
                    Icons.Default.Key,
                    contentDescription = "Backup Private Key",
                    tint = Color.White
                )
            }
        }

        // Connection info
        Text(
            connectionStatus,
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )

        // Logged-in user profile
        if (pubKey != null) {
            Spacer(modifier = Modifier.height(8.dp))

            var showSwitcher by remember { mutableStateOf(false) }
            val accounts by AppModule.accountStore.accounts.collectAsState()
            val activeId by AppModule.accountStore.activeId.collectAsState()
            // Notifications feed for the active account; drives badge
            // recomposition when the active user marks/clears notifications.
            val activeNotifications by AppModule.notificationHistoryStore.entries.collectAsState()

            // User profile card
            Box(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .background(NostrordColors.Background, shape = RoundedCornerShape(8.dp))
                        .clickable { showSwitcher = true }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                val displayName = currentUserMetadata?.displayName
                    ?: currentUserMetadata?.name
                    ?: pubKey.take(8)

                ProfileAvatar(
                    imageUrl = currentUserMetadata?.picture,
                    displayName = displayName,
                    pubkey = pubKey,
                    size = 44.dp
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = displayName,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${pubKey.take(8)}…",
                        color = NostrordColors.TextSecondary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                }

                DropdownMenu(
                    expanded = showSwitcher,
                    onDismissRequest = { showSwitcher = false },
                ) {
                    accounts.forEach { account ->
                        val unread = remember(account.pubkey, activeId, activeNotifications) {
                            AppModule.notificationHistoryStore.unreadCountFor(account.pubkey)
                        }
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        account.label,
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    if (unread > 0) {
                                        Spacer(Modifier.width(8.dp))
                                        UnreadBadge(count = unread)
                                    }
                                    if (account.id == activeId) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(
                                            Icons.Default.Check,
                                            contentDescription = null,
                                            tint = NostrordColors.Primary,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                            },
                            onClick = {
                                showSwitcher = false
                                if (account.id != activeId) {
                                    scope.launch {
                                        AppModule.accountManager.switchAccount(account.id)
                                    }
                                }
                            },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Add account") },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        onClick = {
                            showSwitcher = false
                            onNavigate(Screen.Accounts)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Manage accounts") },
                        leadingIcon = { Icon(Icons.Default.SwitchAccount, contentDescription = null) },
                        onClick = {
                            showSwitcher = false
                            onNavigate(Screen.Accounts)
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    scope.launch {
                        AppModule.nostrRepository.logout()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error)
            ) {
                Text("Logout", color = Color.White)
            }
        } else {
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = { onNavigate(Screen.NostrLogin) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Primary)
            ) {
                Text("Login with Nostr", color = Color.White)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            "Joined Groups",
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(8.dp)
        )

        if (joinedGroups.isEmpty()) {
            Text(
                "No joined groups yet",
                color = NostrordColors.TextSecondary,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(8.dp)
            )
        } else {
            val joinedList = joinedGroups.toList()
            Box(modifier = Modifier.fillMaxSize()) {
                val listState = rememberLazyListState()

                LazyColumn(state = listState) {
                    items(joinedList.size) { index ->
                        val groupId = joinedList[index]
                        val group = groups.find { it.id == groupId }
                        val groupName = group?.name ?: groupId
                        val unreadCount = unreadCounts[groupId] ?: 0
                        val hasUnread = unreadCount > 0

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .background(NostrordColors.Background, RoundedCornerShape(8.dp))
                                .clickable { onNavigate(Screen.Group(groupId, group?.name)) }
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Avatar with unread badge
                            Box {
                                Box(
                                    modifier = Modifier
                                        .size(40.dp)
                                        .clip(CircleShape)
                                        .background(generateColorFromString(groupId)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = groupName.take(1).uppercase(),
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // Unread badge
                                if (hasUnread) {
                                    UnreadBadge(
                                        count = unreadCount,
                                        modifier = Modifier
                                            .align(Alignment.TopEnd)
                                            .offset(x = 4.dp, y = (-4).dp),
                                        size = 16.dp
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(12.dp))

                            Text(
                                text = groupName,
                                color = if (hasUnread) Color.White else NostrordColors.TextSecondary,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (hasUnread) FontWeight.Bold else FontWeight.Normal,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                VerticalScrollbarWrapper(
                    listState = listState,
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight()
                )
            }
        }
    }
}
