package org.nostr.nostrord.web.screens

import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.screens.home.DiscoverGroup
import org.nostr.nostrord.ui.screens.home.Friend
import org.nostr.nostrord.ui.screens.home.HomePageViewModel
import org.nostr.nostrord.ui.screens.home.JoinedGroup
import org.nostr.nostrord.ui.screens.onboarding.onboardingFollowPacks
import org.nostr.nostrord.web.bridge.useStateFlow
import org.nostr.nostrord.web.bridge.useViewModel
import org.nostr.nostrord.web.components.AvatarKind
import org.nostr.nostrord.web.components.Ic
import org.nostr.nostrord.web.components.WebAvatar
import org.nostr.nostrord.web.components.icon
import org.nostr.nostrord.web.components.iconInput
import org.nostr.nostrord.web.components.tabItem
import react.ChildrenBuilder
import react.FC
import react.Props
import react.dom.html.ReactHTML.button
import react.dom.html.ReactHTML.div
import react.dom.html.ReactHTML.h1
import react.dom.html.ReactHTML.h2
import react.dom.html.ReactHTML.p
import react.dom.html.ReactHTML.span
import react.useEffect
import react.useState
import web.cssom.ClassName
import web.html.InputType
import web.html.text

private val FILTERS = listOf("My groups", "From friends", "Recommended", "People")

/** Per-filter icons: own chats, friends, public discovery, people to follow (matches native). */
private val FILTER_ICONS = listOf(Ic.Forum, Ic.People, Ic.ThumbUp, Ic.PersonAdd)

external interface HomePageProps : Props {
    var onOpenGroup: (JoinedGroup) -> Unit
    var onCreateGroup: () -> Unit
    var onJoinGroup: () -> Unit
    var onOpenDms: () -> Unit
    var onOpenNotifications: () -> Unit
    var onOpenDrawer: () -> Unit
}

/**
 * New-design Home (prototype Home): header bar, title + join/create actions, search +
 * filter pills, and the per-filter content. "My groups" shows the real joined groups
 * (kind:10009) via the shared HomePageViewModel; friends / communities are
 * layout-only empty states and People reuses the dummy follow packs until the follow
 * logic lands.
 */
val HomePage =
    FC<HomePageProps> { props ->
        val vm = useViewModel { HomePageViewModel(AppModule.nostrRepository) }
        val myGroups = useStateFlow(vm.myGroups)
        val memberCounts = useStateFlow(vm.memberCounts)
        val query = useStateFlow(vm.query)
        val friends = useStateFlow(vm.friends)
        val friendsGroups = useStateFlow(vm.friendsGroups)
        val recommendedGroups = useStateFlow(vm.recommendedGroups)
        val (filter, setFilter) = useState { 0 }

        // Fetch the discovery lists lazily, only when their tab is shown.
        useEffect(filter) {
            if (filter == 1) vm.loadFriendsGroups()
            if (filter == 2) vm.loadRecommended()
        }

        div {
            className = ClassName("home-page")

            div {
                className = ClassName("page-header")
                button {
                    className = ClassName("icon-btn frame-menu-btn")
                    title = "Menu"
                    onClick = { props.onOpenDrawer() }
                    icon(Ic.Menu)
                }
                icon(Ic.People)
                span {
                    className = ClassName("page-header-title")
                    +"Groups"
                }
                div {
                    className = ClassName("page-header-actions")
                    button {
                        className = ClassName("icon-btn")
                        title = "Direct messages"
                        onClick = { props.onOpenDms() }
                        icon(Ic.Mail)
                    }
                    button {
                        className = ClassName("icon-btn")
                        title = "Notifications"
                        onClick = { props.onOpenNotifications() }
                        icon(Ic.Notifications)
                    }
                }
            }

            div {
                className = ClassName("home-scroll")
                div {
                    className = ClassName("home-content")

                    div {
                        className = ClassName("home-title-row")
                        div {
                            h1 {
                                className = ClassName("home-title")
                                +"Groups"
                            }
                            p {
                                className = ClassName("home-subtitle")
                                +"Your groups and new ones to discover through your friends"
                            }
                        }
                        div {
                            className = ClassName("home-actions")
                            button {
                                className = ClassName("btn-secondary")
                                onClick = { props.onJoinGroup() }
                                icon(Ic.Link)
                                +"Join group"
                            }
                            button {
                                className = ClassName("btn-primary")
                                onClick = { props.onCreateGroup() }
                                icon(Ic.Add)
                                +"Create group"
                            }
                        }
                    }

                    div {
                        className = ClassName("home-toolbar")
                        div {
                            className = ClassName("tab-strip")
                            FILTERS.forEachIndexed { index, label ->
                                tabItem(filter == index, FILTER_ICONS[index], label) { setFilter(index) }
                            }
                        }
                        div {
                            className = ClassName("home-search")
                            iconInput(
                                ic = Ic.Search,
                                type = InputType.text,
                                placeholder = if (filter == 3) "Filter follow packs" else "Filter groups",
                                value = query,
                                onChange = { vm.setQuery(it) },
                                onEscape = { vm.setQuery("") },
                            )
                        }
                    }

                    when (filter) {
                        0 ->
                            when {
                                myGroups.isEmpty() && query.isBlank() ->
                                    emptyCard(
                                        emoji = "👋",
                                        title = "You're not in any group yet",
                                        description =
                                        "Join through an invite link, or check From friends for the " +
                                            "groups where people you follow already are.",
                                    ) {
                                        button {
                                            className = ClassName("btn-primary")
                                            onClick = { props.onJoinGroup() }
                                            icon(Ic.Link)
                                            +"Join by link"
                                        }
                                        button {
                                            className = ClassName("btn-secondary")
                                            onClick = { setFilter(1) }
                                            +"See friends' groups"
                                        }
                                    }
                                myGroups.isEmpty() ->
                                    emptyCard("🔍", "No group found", "Try another search term.")
                                else ->
                                    div {
                                        className = ClassName("card-grid")
                                        myGroups.forEach { group ->
                                            groupCard(group, memberCounts[group.meta.id] ?: 0) {
                                                props.onOpenGroup(group)
                                            }
                                        }
                                    }
                            }
                        1 ->
                            when {
                                friends.isEmpty() ->
                                    emptyCard(
                                        emoji = "🫂",
                                        title = "You don't follow anyone yet",
                                        description = "Follow some people to see your friends here and the groups where they are.",
                                    ) {
                                        button {
                                            className = ClassName("btn-secondary")
                                            onClick = { setFilter(3) }
                                            +"See people to follow"
                                        }
                                    }
                                friendsGroups.isEmpty() ->
                                    emptyCard(
                                        emoji = "🔭",
                                        title = "No groups from your friends yet",
                                        description = "When people you follow join groups, those groups show up here to discover.",
                                    )
                                else ->
                                    div {
                                        className = ClassName("card-grid")
                                        friendsGroups.forEach { fg ->
                                            discoverGroupCard(fg) {
                                                props.onOpenGroup(JoinedGroup(fg.relayUrl, fg.meta))
                                            }
                                        }
                                    }
                            }
                        2 ->
                            if (recommendedGroups.isEmpty()) {
                                emptyCard(
                                    emoji = "✨",
                                    title = "No recommendations yet",
                                    description = "Hand-picked groups we curate will show up here.",
                                )
                            } else {
                                div {
                                    className = ClassName("card-grid")
                                    recommendedGroups.forEach { group ->
                                        discoverGroupCard(group) {
                                            props.onOpenGroup(JoinedGroup(group.relayUrl, group.meta))
                                        }
                                    }
                                }
                            }
                        else ->
                            div {
                                className = ClassName("onb-pack-list")
                                // Layout-only: dummy packs shared with the onboarding step,
                                // filtered by the "Filter follow packs" box.
                                val needle = query.trim().lowercase()
                                onboardingFollowPacks.filter {
                                    needle.isEmpty() ||
                                        it.name.lowercase().contains(needle) ||
                                        it.description.lowercase().contains(needle)
                                }.forEach { pack ->
                                    button {
                                        key = pack.name
                                        className = ClassName("pack-card")
                                        span {
                                            className = ClassName("pack-card-emoji")
                                            +pack.emoji
                                        }
                                        div {
                                            className = ClassName("pack-card-body")
                                            div {
                                                className = ClassName("pack-card-name")
                                                +pack.name
                                            }
                                            div {
                                                className = ClassName("pack-card-desc")
                                                +pack.description
                                            }
                                            div {
                                                className = ClassName("pack-card-count")
                                                +"${pack.people} people"
                                            }
                                        }
                                        span {
                                            className = ClassName("pack-card-chip")
                                            +"View people ›"
                                        }
                                    }
                                }
                            }
                    }
                }
            }
        }
    }

private fun ChildrenBuilder.groupCard(
    group: JoinedGroup,
    memberCount: Int,
    onOpen: () -> Unit,
) {
    val meta = group.meta
    val name = meta.name ?: meta.id
    button {
        key = meta.id
        className = ClassName("group-card")
        onClick = { onOpen() }
        div {
            className = ClassName("group-card-head")
            WebAvatar {
                url = meta.picture
                seed = meta.id
                this.name = name
                kind = AvatarKind.GROUP
                cls = "group-card-avatar"
            }
            div {
                div {
                    className = ClassName("group-card-name")
                    +name
                }
                div {
                    className = ClassName("group-card-meta")
                    if (memberCount > 0) {
                        icon(Ic.People)
                        +"$memberCount"
                    }
                    if (meta.isRestricted) {
                        span {
                            className = ClassName("badge-restricted")
                            +"restricted"
                        }
                    }
                }
            }
        }
        p {
            className = ClassName("group-card-desc")
            +(meta.about.orEmpty().ifBlank { "No description" })
        }
    }
}

/**
 * Discovery card for the From friends / Recommended tabs: group name, the people in
 * it by name, then their overlapping avatars and the total "N people" count. [people]
 * is the friends you follow who are here, falling back to members on Recommended.
 */
private fun ChildrenBuilder.discoverGroupCard(
    dg: DiscoverGroup,
    onOpen: () -> Unit,
) {
    val meta = dg.meta
    val name = meta.name ?: meta.id
    button {
        key = meta.id
        className = ClassName("group-card")
        onClick = { onOpen() }
        div {
            className = ClassName("group-card-head")
            WebAvatar {
                url = meta.picture
                seed = meta.id
                this.name = name
                kind = AvatarKind.GROUP
                cls = "group-card-avatar"
            }
            div {
                div {
                    className = ClassName("group-card-name")
                    +name
                }
                if (!meta.isOpen) {
                    div {
                        className = ClassName("group-card-meta")
                        span {
                            className = ClassName("badge-restricted")
                            +"restricted"
                        }
                    }
                }
            }
        }
        if (dg.people.isNotEmpty()) {
            div {
                className = ClassName("group-card-people")
                div {
                    className = ClassName("discover-avatars")
                    dg.people.take(5).forEach { person ->
                        WebAvatar {
                            url = person.metadata?.picture
                            seed = person.pubkey
                            this.name = personName(person)
                            cls = "discover-avatar"
                        }
                    }
                }
                span {
                    className = ClassName("group-card-people-count")
                    +"${maxOf(dg.memberCount, dg.people.size)} people"
                }
            }
        }
    }
}

/** A person's display name for a discovery card: profile name, then a short npub. */
private fun personName(friend: Friend): String = friend.metadata?.displayName?.takeIf { it.isNotBlank() }
    ?: friend.metadata?.name?.takeIf { it.isNotBlank() }
    ?: (runCatching { Nip19.encodeNpub(friend.pubkey) }.getOrDefault(friend.pubkey).take(10) + "…")

private fun ChildrenBuilder.emptyCard(
    emoji: String,
    title: String,
    description: String,
    actions: (ChildrenBuilder.() -> Unit)? = null,
) {
    div {
        className = ClassName("empty-card")
        div {
            className = ClassName("empty-card-emoji")
            +emoji
        }
        h2 {
            className = ClassName("empty-card-title")
            +title
        }
        p {
            className = ClassName("empty-card-desc")
            +description
        }
        actions?.let {
            div {
                className = ClassName("empty-card-actions")
                it()
            }
        }
    }
}
