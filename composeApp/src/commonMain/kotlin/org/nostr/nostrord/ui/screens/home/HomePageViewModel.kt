package org.nostr.nostrord.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.UserGroupRef
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.notifications.NotificationHistoryStore
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.loadFriendsCacheFor
import org.nostr.nostrord.storage.saveFriendsCacheFor

/**
 * Curator whose public group list (kind:10009) seeds the "Recommended" tab: the
 * groups we hand-pick are simply the ones this account joins.
 */
private const val CURATOR_PUBKEY = "b2cdcb37d32533145c00c4f43d5e1e1deb7c67bceea7ef63f526ca4cab891633"

/** A joined group together with the relay that hosts it (needed for navigation). */
data class JoinedGroup(
    val relayUrl: String,
    val meta: GroupMetadata,
)

/** A followed user (NIP-02 kind:3) with their resolved kind:0 metadata, if known. */
data class Friend(
    val pubkey: String,
    val metadata: UserMetadata?,
)

/**
 * A discoverable group (you are NOT already in) shown on the From friends and
 * Recommended tabs. [people] is the preview shown on the card: the friends you
 * follow who are in the group (From friends, and Recommended when any), falling
 * back to the group's members as social proof on Recommended. [memberCount] is
 * the total used for the "N people" label.
 */
data class DiscoverGroup(
    val relayUrl: String,
    val meta: GroupMetadata,
    val people: List<Friend>,
    val memberCount: Int,
)

/**
 * Screen logic for the new-design Home page (prototype Home), shared by the Compose
 * and React UIs: the user's joined groups across every relay (kind:10009), filtered
 * by the search query. The friends / communities / people tabs are layout-only with
 * placeholder content for now, so they carry no logic here yet.
 */
class HomePageViewModel(
    private val repo: NostrRepositoryApi,
    notificationHistoryStore: NotificationHistoryStore = NotificationHistoryStore(),
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    /** The "Filter groups" box matches a group by name or about (empty needle = all). */
    private fun matchesQuery(
        meta: GroupMetadata,
        needle: String,
    ): Boolean = needle.isEmpty() ||
        (meta.name ?: meta.id).lowercase().contains(needle) ||
        meta.about.orEmpty().lowercase().contains(needle)

    /** Unread message counts per group id (drives the rail badges). */
    val unreadCounts: StateFlow<Map<String, Int>> = repo.unreadCounts

    /** Unread notifications (drives the bell badge on the groups rail). */
    val notificationUnread: StateFlow<Int> =
        notificationHistoryStore.entries
            .map { list -> list.count { !it.read } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Known member counts per group id (only groups whose member list was fetched). */
    val memberCounts: StateFlow<Map<String, Int>> =
        repo.groupMembers
            .map { members -> members.mapValues { it.value.size } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * Joined groups across all relays (each with its hosting relay), with metadata
     * where the relay already served the kind:39000 (a bare id placeholder
     * otherwise), filtered by [query] over name and description. Carries the same
     * people preview + member count as the discovery tabs so every group card shows
     * the same shape: the friends you follow who are here (or members as a fallback).
     */
    val myGroups: StateFlow<List<DiscoverGroup>> =
        combine(
            listOf(
                repo.groupsByRelay,
                repo.joinedGroupsByRelay,
                repo.groupMembers,
                repo.following,
                repo.userMetadata,
                _query,
            ),
        ) { arr ->
            val groupsByRelay = arr[0] as Map<String, List<GroupMetadata>>
            val joinedByRelay = arr[1] as Map<String, Set<String>>
            val members = arr[2] as Map<String, List<String>>
            val following = arr[3] as Set<String>
            val meta = arr[4] as Map<String, UserMetadata>
            val needle = (arr[5] as String).trim().lowercase()
            joinedByRelay
                .flatMap { (relay, ids) ->
                    val metas = groupsByRelay[relay].orEmpty().associateBy { it.id }
                    ids.map { id ->
                        val memberPks = members[id].orEmpty()
                        val friendsHere = memberPks.filter { it in following }
                        val preview = (friendsHere.ifEmpty { memberPks }).take(8)
                        DiscoverGroup(
                            relayUrl = relay,
                            meta =
                            metas[id] ?: GroupMetadata(
                                id = id,
                                name = null,
                                about = null,
                                picture = null,
                                isPublic = true,
                                isOpen = true,
                            ),
                            people = preview.map { Friend(it, meta[it]) },
                            memberCount = memberPks.size,
                        )
                    }
                }
                .distinctBy { it.meta.id }
                .filter { dg ->
                    needle.isEmpty() ||
                        (dg.meta.name ?: dg.meta.id).lowercase().contains(needle) ||
                        dg.meta.about.orEmpty().lowercase().contains(needle)
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Last-known friends persisted to disk (followed users + their kind:0 metadata),
     * so the sidebar shows names + avatars instantly on launch instead of waiting for
     * kind:3 + kind:0 to re-arrive. Refreshed below whenever live data resolves.
     */
    private val cachedFriends: List<Friend> =
        SecureStorage.loadFriendsCacheFor(repo.getPublicKey().orEmpty())
            .map { Friend(it.pubkey, it) }

    /** Friends with resolved avatars first, then by display name. */
    private fun sortFriends(list: List<Friend>): List<Friend> = list.sortedWith(
        compareByDescending<Friend> { it.metadata?.picture?.isNotBlank() == true }
            .thenBy { (it.metadata?.displayName ?: it.metadata?.name ?: it.pubkey).lowercase() },
    )

    /**
     * The users the active account follows (kind:3), each enriched with their kind:0
     * metadata (live, or the disk cache until it re-arrives), avatars first. Falls back
     * to the cached list while kind:3 is still loading so the sidebar isn't empty.
     */
    val friends: StateFlow<List<Friend>> =
        combine(repo.following, repo.userMetadata) { following, meta ->
            if (following.isEmpty()) {
                // kind:3 not loaded yet (or genuinely empty): show the cache meanwhile.
                sortFriends(cachedFriends)
            } else {
                val cacheByPk = cachedFriends.associateBy { it.pubkey }
                sortFriends(following.map { pk -> Friend(pk, meta[pk] ?: cacheByPk[pk]?.metadata) })
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, sortFriends(cachedFriends))

    /**
     * True while we don't yet know who the user follows AND have nothing to show: the
     * sidebar renders a skeleton instead of the "You don't follow anyone yet." empty
     * state, which must appear only once we're sure the list is genuinely empty.
     */
    private val _contactsResolved = MutableStateFlow(cachedFriends.isNotEmpty())
    val friendsLoading: StateFlow<Boolean> =
        combine(_contactsResolved, friends) { resolved, fr -> !resolved && fr.isEmpty() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, cachedFriends.isEmpty())

    /**
     * Groups people you follow are in that you are NOT already in (discovery via
     * the social graph). Two signals are merged per group: a friend's public
     * kind:10009 list, and friends who appear in a group's member list (kind:39002)
     * for groups whose metadata we already have. Ranked by friend overlap, then name.
     * Populated on demand by [loadFriendsGroups] (the "From friends" tab).
     */
    @Suppress("UNCHECKED_CAST")
    val friendsGroups: StateFlow<List<DiscoverGroup>> =
        combine(
            listOf(
                repo.following,
                repo.userGroupLists,
                repo.joinedGroupsByRelay,
                repo.groupsByRelay,
                repo.groupMembers,
                repo.userMetadata,
                _query,
            ),
        ) { arr ->
            val following = arr[0] as Set<String>
            val lists = arr[1] as Map<String, List<UserGroupRef>>
            val joinedByRelay = arr[2] as Map<String, Set<String>>
            val byRelay = arr[3] as Map<String, List<GroupMetadata>>
            val members = arr[4] as Map<String, List<String>>
            val meta = arr[5] as Map<String, UserMetadata>
            val needle = (arr[6] as String).trim().lowercase()

            if (following.isEmpty()) return@combine emptyList()
            val myGroupIds = joinedByRelay.values.flatten().toSet()
            val friendsByGroup = LinkedHashMap<String, LinkedHashSet<String>>()
            val relayByGroup = HashMap<String, String>()

            // Signal 1: each friend's public kind:10009 group refs.
            following.forEach { friend ->
                lists[friend].orEmpty().forEach { ref ->
                    if (ref.groupId in myGroupIds) return@forEach
                    friendsByGroup.getOrPut(ref.groupId) { LinkedHashSet() }.add(friend)
                    if (ref.groupId !in relayByGroup && ref.relayUrl.isNotBlank()) {
                        relayByGroup[ref.groupId] = ref.relayUrl
                    }
                }
            }

            // Signal 2: friends present in a visible group's member list (kind:39002).
            members.forEach { (gid, memberPks) ->
                if (gid in myGroupIds) return@forEach
                val friendMembers = memberPks.filter { it in following }
                if (friendMembers.isEmpty()) return@forEach
                friendsByGroup.getOrPut(gid) { LinkedHashSet() }.addAll(friendMembers)
                if (gid !in relayByGroup) {
                    byRelay.entries.firstOrNull { e -> e.value.any { it.id == gid } }?.let { relayByGroup[gid] = it.key }
                }
            }

            friendsByGroup.entries
                .mapNotNull { (gid, friendPks) ->
                    val relay = relayByGroup[gid] ?: return@mapNotNull null
                    val m = byRelay[relay].orEmpty().find { it.id == gid }
                        ?: byRelay.values.flatten().find { it.id == gid }
                    DiscoverGroup(
                        relayUrl = relay,
                        meta =
                        m ?: GroupMetadata(
                            id = gid,
                            name = null,
                            about = null,
                            picture = null,
                            isPublic = true,
                            isOpen = true,
                        ),
                        people = friendPks.map { Friend(it, meta[it]) },
                        // Prefer the relay's full member count; fall back to the
                        // known friends until the member list (kind:39002) arrives.
                        memberCount = members[gid].orEmpty().size.takeIf { it > 0 } ?: friendPks.size,
                    )
                }
                .filter { matchesQuery(it.meta, needle) }
                .sortedWith(
                    compareByDescending<DiscoverGroup> { it.people.size }
                        .thenBy { (it.meta.name ?: it.meta.id).lowercase() },
                )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Curated "Recommended" groups: the [CURATOR_PUBKEY]'s public kind:10009 list,
     * minus the groups you already joined. Populated on demand by [loadRecommended].
     */
    @Suppress("UNCHECKED_CAST")
    val recommendedGroups: StateFlow<List<DiscoverGroup>> =
        combine(
            listOf(
                repo.userGroupLists,
                repo.joinedGroupsByRelay,
                repo.groupsByRelay,
                repo.following,
                repo.groupMembers,
                repo.userMetadata,
                _query,
            ),
        ) { arr ->
            val lists = arr[0] as Map<String, List<UserGroupRef>>
            val joinedByRelay = arr[1] as Map<String, Set<String>>
            val byRelay = arr[2] as Map<String, List<GroupMetadata>>
            val following = arr[3] as Set<String>
            val members = arr[4] as Map<String, List<String>>
            val meta = arr[5] as Map<String, UserMetadata>
            val needle = (arr[6] as String).trim().lowercase()
            val myGroupIds = joinedByRelay.values.flatten().toSet()
            lists[CURATOR_PUBKEY].orEmpty()
                .filter { it.groupId !in myGroupIds }
                .map { ref ->
                    val gid = ref.groupId
                    val memberPks = members[gid].orEmpty()
                    // "Friends, otherwise members": surface the people you follow who
                    // are here; if none, fall back to members as social proof.
                    val friendsHere = memberPks.filter { it in following }
                    val preview = (friendsHere.ifEmpty { memberPks }).take(8)
                    DiscoverGroup(
                        relayUrl = ref.relayUrl,
                        meta =
                        byRelay[ref.relayUrl].orEmpty().find { it.id == gid }
                            ?: byRelay.values.flatten().find { it.id == gid }
                            ?: GroupMetadata(
                                id = gid,
                                name = null,
                                about = null,
                                picture = null,
                                isPublic = true,
                                isOpen = true,
                            ),
                        people = preview.map { Friend(it, meta[it]) },
                        memberCount = memberPks.size,
                    )
                }
                .filter { matchesQuery(it.meta, needle) }
                .distinctBy { it.meta.id }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var recommendedRequested = false

    /** Fetch the curator's kind:10009 once, when the "Recommended" tab opens. */
    fun loadRecommended() {
        if (recommendedRequested) return
        recommendedRequested = true
        viewModelScope.launch { repo.requestUserGroupList(CURATOR_PUBKEY) }
    }

    /** Friends whose kind:10009 we've already requested, so a tab re-open doesn't refire. */
    private val requestedFriendLists = mutableSetOf<String>()

    /** "relayUrl|groupId" pairs already sent for metadata, so we request each once. */
    private val requestedPreviews = mutableSetOf<String>()

    /** Group ids whose member list (kind:39002) we've already requested for a card. */
    private val requestedMembers = mutableSetOf<String>()

    /** Pubkeys whose kind:0 we've requested for a Recommended card preview. */
    private val requestedPeopleMeta = mutableSetOf<String>()

    /**
     * Fetch the public group list (kind:10009) of each followed user not yet
     * requested. Called when the "From friends" tab opens so we don't pay this on
     * cold start; picks up newly followed users on a later call.
     */
    fun loadFriendsGroups() {
        viewModelScope.launch {
            val pks = repo.following.value - requestedFriendLists
            if (pks.isEmpty()) return@launch
            requestedFriendLists.addAll(pks)
            pks.forEach { repo.requestUserGroupList(it) }
        }
    }

    init {
        // Load the own contact list and resolve names for each followed user so the
        // Friends list renders display names rather than bare npubs.
        viewModelScope.launch { repo.requestContactList() }
        viewModelScope.launch {
            repo.following.collect { pks -> if (pks.isNotEmpty()) repo.requestUserMetadata(pks) }
        }
        // Stop showing the skeleton once we know who the user follows, or after a short
        // grace period (covers the genuinely-follows-nobody case, where kind:3 never
        // arrives). With a non-empty cache this is already resolved at construction.
        viewModelScope.launch {
            withTimeoutOrNull(3_500) { repo.following.first { it.isNotEmpty() } }
            _contactsResolved.value = true
        }
        // Persist the friends list (followed users + resolved metadata) so the next
        // launch shows avatars instantly. Only once kind:3 has loaded (so we don't
        // overwrite the cache with the empty pre-load state); distinctUntilChanged
        // keeps the metadata stream from re-writing identical snapshots.
        viewModelScope.launch {
            friends
                .map { list -> list.mapNotNull { it.metadata } }
                .distinctUntilChanged()
                .collect { metas ->
                    val pk = repo.getPublicKey() ?: return@collect
                    if (repo.following.value.isNotEmpty()) {
                        SecureStorage.saveFriendsCacheFor(pk, metas)
                    }
                }
        }
        // Backfill metadata for discovered friend groups. We aggregate every friend's
        // kind:10009 refs into a deduplicated relayUrl -> {groupId} map (each group once,
        // each relay once) and hand it to the repo, which connects to each relay a single
        // time and batches one kind:39000 REQ. This avoids re-requesting the same group
        // and avoids opening one connection per (friend, group) ref.
        viewModelScope.launch {
            combine(repo.following, repo.userGroupLists, repo.joinedGroupsByRelay) { following, lists, joinedByRelay ->
                val myGroupIds = joinedByRelay.values.flatten().toSet()
                val relayToGroups = HashMap<String, MutableSet<String>>()
                // Curated (recommended) groups need metadata too; fold them into the
                // same deduped per-relay fetch as the friends' groups.
                (following + CURATOR_PUBKEY).forEach { friend ->
                    lists[friend].orEmpty().forEach { ref ->
                        if (ref.relayUrl.isBlank() || ref.groupId in myGroupIds) return@forEach
                        // Only schedule a (relay, group) pair once per session.
                        if (requestedPreviews.add("${ref.relayUrl}|${ref.groupId}")) {
                            relayToGroups.getOrPut(ref.relayUrl) { mutableSetOf() }.add(ref.groupId)
                        }
                    }
                }
                relayToGroups
            }.collect { relayToGroups ->
                if (relayToGroups.isNotEmpty()) repo.fetchGroupPreviews(relayToGroups)
            }
        }
        // Member lists (kind:39002) for every group card (My groups + From friends +
        // Recommended), so each shows a real "N people" count and member avatars and the
        // people-row skeleton resolves. Each group is requested once; the discovery flows
        // only emit after their tab loads, so this stays off the cold-start path.
        viewModelScope.launch {
            combine(myGroups, friendsGroups, recommendedGroups) { a, b, c -> a + b + c }
                .collect { discovered ->
                    discovered.forEach { g ->
                        if (requestedMembers.add(g.meta.id)) {
                            launch { repo.requestGroupMembers(g.meta.id) }
                        }
                    }
                }
        }
        // Resolve kind:0 for the people previewed on My groups / Recommended cards
        // (members who are not already followed have no metadata yet). Friends' metadata
        // is already fetched above from [following].
        viewModelScope.launch {
            combine(myGroups, recommendedGroups) { a, b -> a + b }
                .map { groups -> groups.flatMap { it.people }.map { it.pubkey }.toSet() }
                .collect { pks ->
                    val missing = pks - requestedPeopleMeta
                    if (missing.isNotEmpty()) {
                        requestedPeopleMeta.addAll(missing)
                        repo.requestUserMetadata(missing)
                    }
                }
        }
    }
}
