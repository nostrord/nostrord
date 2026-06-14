package org.nostr.nostrord.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Mail
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.buttons.AppButtonVariant
import org.nostr.nostrord.ui.components.forms.AppSegmentedTabs
import org.nostr.nostrord.ui.components.forms.SegmentedTab
import org.nostr.nostrord.ui.components.home.EmptyStateCard
import org.nostr.nostrord.ui.components.home.GroupCard
import org.nostr.nostrord.ui.components.onboarding.PackCard
import org.nostr.nostrord.ui.screens.onboarding.onboardingFollowPacks
import org.nostr.nostrord.ui.theme.NostrordColors

private val FILTERS = listOf("My groups", "From friends", "Recommended", "People")

/** Per-filter icons: own chats, friends, public discovery, people to follow (matches web). */
private val FILTER_ICONS = listOf(
    Icons.Default.Forum,
    Icons.Default.People,
    Icons.Default.ThumbUp,
    Icons.Default.PersonAdd,
)

/**
 * New-design Home (prototype Home): header bar, title + join/create actions, search +
 * filter pills, and the per-filter content. "My groups" shows the real joined groups
 * (kind:10009); friends / communities are layout-only empty states and People reuses
 * the dummy follow packs until the follow logic lands.
 */
@Composable
fun HomePageScreen(
    modifier: Modifier = Modifier,
    onOpenGroup: (JoinedGroup) -> Unit = {},
    onCreateGroup: () -> Unit = {},
    onJoinGroup: () -> Unit = {},
    onOpenDms: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
) {
    val vm = viewModel { HomePageViewModel(AppModule.nostrRepository) }
    val myGroups by vm.myGroups.collectAsState()
    val memberCounts by vm.memberCounts.collectAsState()
    val query by vm.query.collectAsState()
    val friends by vm.friends.collectAsState()
    val friendsGroups by vm.friendsGroups.collectAsState()
    val recommendedGroups by vm.recommendedGroups.collectAsState()
    var filter by remember { mutableStateOf(0) }

    // Fetch the discovery lists lazily, only when their tab is shown.
    LaunchedEffect(filter) {
        if (filter == 1) vm.loadFriendsGroups()
        if (filter == 2) vm.loadRecommended()
    }

    Column(modifier = modifier.fillMaxSize().background(NostrordColors.Background)) {
        // Header bar
        Row(
            modifier = Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.People,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Groups",
                color = NostrordColors.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.weight(1f))
            IconButton(onClick = onOpenDms) {
                Icon(
                    imageVector = Icons.Default.Mail,
                    contentDescription = "Direct messages",
                    tint = NostrordColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
            IconButton(onClick = onOpenNotifications) {
                Icon(
                    imageVector = Icons.Default.Notifications,
                    contentDescription = "Notifications",
                    tint = NostrordColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        HorizontalDivider(color = NostrordColors.Divider)

        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val columns =
                when {
                    maxWidth > 1024.dp -> 3
                    maxWidth > 640.dp -> 2
                    else -> 1
                }
            Column(
                modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier =
                    Modifier
                        .widthIn(max = 1024.dp)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 24.dp),
                ) {
                    // Title (left) + actions (right) on one row, matching the web layout.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Groups",
                                color = NostrordColors.TextPrimary,
                                fontSize = 24.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                "Your groups and new ones to discover through your friends",
                                color = NostrordColors.TextMuted,
                                fontSize = 14.sp,
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppButton(
                                text = "Join group",
                                onClick = onJoinGroup,
                                variant = AppButtonVariant.Secondary,
                                icon = Icons.Default.Link,
                            )
                            AppButton(
                                text = "Create group",
                                onClick = onCreateGroup,
                                icon = Icons.Default.Add,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Filter pills, then the per-tab "Filter groups" box below them.
                    AppSegmentedTabs(
                        tabs = FILTERS.mapIndexed { i, label -> SegmentedTab(label, FILTER_ICONS[i]) },
                        selectedIndex = filter,
                        onSelect = { filter = it },
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    HomeFilterField(
                        value = query,
                        onValueChange = { vm.setQuery(it) },
                        placeholder = if (filter == 3) "Filter follow packs" else "Filter groups",
                        onEscape = { vm.setQuery("") },
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    when (filter) {
                        0 ->
                            when {
                                myGroups.isEmpty() && query.isBlank() ->
                                    EmptyStateCard(
                                        emoji = "👋",
                                        title = "You're not in any group yet",
                                        description =
                                        "Join through an invite link, or check From friends " +
                                            "for the groups where people you follow already are.",
                                    ) {
                                        AppButton(
                                            text = "Join by link",
                                            onClick = onJoinGroup,
                                            icon = Icons.Default.Link,
                                        )
                                        AppButton(
                                            text = "See friends' groups",
                                            onClick = { filter = 1 },
                                            variant = AppButtonVariant.Secondary,
                                        )
                                    }
                                myGroups.isEmpty() ->
                                    EmptyStateCard(
                                        emoji = "🔍",
                                        title = "No group found",
                                        description = "Try another search term.",
                                    )
                                else ->
                                    CardGrid(columns) {
                                        myGroups.map { group ->
                                            {
                                                GroupCard(
                                                    name = group.meta.name ?: group.meta.id,
                                                    description = group.meta.about,
                                                    picture = group.meta.picture,
                                                    groupId = group.meta.id,
                                                    memberCount = memberCounts[group.meta.id] ?: 0,
                                                    restricted = group.meta.isRestricted,
                                                    onClick = { onOpenGroup(group) },
                                                )
                                            }
                                        }
                                    }
                            }
                        1 ->
                            when {
                                friends.isEmpty() ->
                                    EmptyStateCard(
                                        emoji = "🫂",
                                        title = "You don't follow anyone yet",
                                        description = "Follow some people to see your friends here and the groups where they are.",
                                    ) {
                                        AppButton(
                                            text = "See people to follow",
                                            onClick = { filter = 3 },
                                            variant = AppButtonVariant.Secondary,
                                        )
                                    }
                                friendsGroups.isEmpty() ->
                                    EmptyStateCard(
                                        emoji = "🔭",
                                        title = "No groups from your friends yet",
                                        description = "When people you follow join groups, those groups show up here to discover.",
                                    )
                                else ->
                                    CardGrid(columns) {
                                        friendsGroups.map { fg ->
                                            {
                                                GroupCard(
                                                    name = fg.meta.name ?: fg.meta.id,
                                                    description = fg.meta.about,
                                                    picture = fg.meta.picture,
                                                    groupId = fg.meta.id,
                                                    memberCount = fg.memberCount,
                                                    restricted = fg.meta.isRestricted,
                                                    people = fg.people,
                                                    onClick = { onOpenGroup(JoinedGroup(fg.relayUrl, fg.meta)) },
                                                )
                                            }
                                        }
                                    }
                            }
                        2 ->
                            if (recommendedGroups.isEmpty()) {
                                EmptyStateCard(
                                    emoji = "✨",
                                    title = "No recommendations yet",
                                    description = "Hand-picked groups we curate will show up here.",
                                )
                            } else {
                                CardGrid(columns) {
                                    recommendedGroups.map { group ->
                                        {
                                            GroupCard(
                                                name = group.meta.name ?: group.meta.id,
                                                description = group.meta.about,
                                                picture = group.meta.picture,
                                                groupId = group.meta.id,
                                                memberCount = group.memberCount,
                                                restricted = group.meta.isRestricted,
                                                people = group.people,
                                                onClick = { onOpenGroup(JoinedGroup(group.relayUrl, group.meta)) },
                                            )
                                        }
                                    }
                                }
                            }
                        else ->
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                // Layout-only: dummy packs shared with the onboarding step,
                                // filtered by the "Filter follow packs" box.
                                val needle = query.trim().lowercase()
                                onboardingFollowPacks
                                    .filter {
                                        needle.isEmpty() ||
                                            it.name.lowercase().contains(needle) ||
                                            it.description.lowercase().contains(needle)
                                    }
                                    .forEach { pack ->
                                        PackCard(
                                            emoji = pack.emoji,
                                            name = pack.name,
                                            description = pack.description,
                                            people = pack.people,
                                        )
                                    }
                            }
                    }
                }
            }
        }
    }
}

/**
 * Compact filter field matching the web `.input-group`: a filled, rounded box with a
 * search icon and a flat text field (not Material's taller OutlinedTextField). Esc clears
 * and drops focus.
 */
@Composable
private fun HomeFilterField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onEscape: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    Row(
        modifier =
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(NostrordColors.BackgroundFloating)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Default.Search,
            contentDescription = null,
            tint = NostrordColors.TextMuted,
            modifier = Modifier.size(18.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = NostrordColors.TextMuted, fontSize = 14.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = NostrordColors.TextContent, fontSize = 14.sp),
                cursorBrush = SolidColor(NostrordColors.Primary),
                modifier =
                Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                            onEscape()
                            focusManager.clearFocus()
                            true
                        } else {
                            false
                        }
                    },
            )
        }
    }
}

/** Fixed-column grid built from rows (content is small; avoids nested lazy scrolling). */
@Composable
private fun CardGrid(
    columns: Int,
    content: () -> List<@Composable () -> Unit>,
) {
    val cards = content()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        cards.chunked(columns).forEach { row ->
            // IntrinsicSize.Max makes every card in the row as tall as the tallest one,
            // so a short card doesn't leave the row ragged (cards fill the height and pin
            // their CTA to the bottom). Mirrors the web grid's equal-height rows.
            Row(
                modifier = Modifier.height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                row.forEach { card ->
                    Column(modifier = Modifier.weight(1f).fillMaxHeight()) { card() }
                }
                repeat(columns - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
