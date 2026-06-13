package org.nostr.nostrord.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.network.UserGroupRef
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.notifications.NotificationHistoryStore

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
 * A group discovered through the social graph: a group some of the people you
 * follow are in, that you are NOT already in. [mutualFriends] are those friends,
 * for the "Alice, Bob +2" hint and ranking.
 */
data class FriendsGroup(
    val relayUrl: String,
    val meta: GroupMetadata,
    val mutualFriends: List<Friend>,
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
     * otherwise), filtered by [query] over name and description.
     */
    val myGroups: StateFlow<List<JoinedGroup>> =
        combine(repo.groupsByRelay, repo.joinedGroupsByRelay, _query) { groupsByRelay, joinedByRelay, q ->
            val joined =
                joinedByRelay
                    .flatMap { (relay, ids) ->
                        val metas = groupsByRelay[relay].orEmpty().associateBy { it.id }
                        ids.map { id ->
                            JoinedGroup(
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
                            )
                        }
                    }.distinctBy { it.meta.id }
            val needle = q.trim().lowercase()
            if (needle.isEmpty()) {
                joined
            } else {
                joined.filter {
                    (it.meta.name ?: it.meta.id).lowercase().contains(needle) ||
                        it.meta.about.orEmpty().lowercase().contains(needle)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The users the active account follows (kind:3), each enriched with their kind:0
     * metadata when known, sorted by display name. Drives the Friends list in the
     * home sidebar.
     */
    val friends: StateFlow<List<Friend>> =
        combine(repo.following, repo.userMetadata) { following, meta ->
            following
                .map { pk -> Friend(pk, meta[pk]) }
                .sortedBy {
                    (it.metadata?.displayName ?: it.metadata?.name ?: it.pubkey).lowercase()
                }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Groups people you follow are in that you are NOT already in (discovery via
     * the social graph). Two signals are merged per group: a friend's public
     * kind:10009 list, and friends who appear in a group's member list (kind:39002)
     * for groups whose metadata we already have. Ranked by friend overlap, then name.
     * Populated on demand by [loadFriendsGroups] (the "From friends" tab).
     */
    @Suppress("UNCHECKED_CAST")
    val friendsGroups: StateFlow<List<FriendsGroup>> =
        combine(
            listOf(
                repo.following,
                repo.userGroupLists,
                repo.joinedGroupsByRelay,
                repo.groupsByRelay,
                repo.groupMembers,
                repo.userMetadata,
            ),
        ) { arr ->
            val following = arr[0] as Set<String>
            val lists = arr[1] as Map<String, List<UserGroupRef>>
            val joinedByRelay = arr[2] as Map<String, Set<String>>
            val byRelay = arr[3] as Map<String, List<GroupMetadata>>
            val members = arr[4] as Map<String, List<String>>
            val meta = arr[5] as Map<String, UserMetadata>

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
                    FriendsGroup(
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
                        mutualFriends = friendPks.map { Friend(it, meta[it]) },
                        memberCount = members[gid].orEmpty().size,
                    )
                }
                .sortedWith(
                    compareByDescending<FriendsGroup> { it.mutualFriends.size }
                        .thenBy { (it.meta.name ?: it.meta.id).lowercase() },
                )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * Curated "Recommended" groups: the [CURATOR_PUBKEY]'s public kind:10009 list,
     * minus the groups you already joined. Populated on demand by [loadRecommended].
     */
    val recommendedGroups: StateFlow<List<JoinedGroup>> =
        combine(repo.userGroupLists, repo.joinedGroupsByRelay, repo.groupsByRelay) { lists, joinedByRelay, byRelay ->
            val myGroupIds = joinedByRelay.values.flatten().toSet()
            lists[CURATOR_PUBKEY].orEmpty()
                .filter { it.groupId !in myGroupIds }
                .map { ref ->
                    val meta = byRelay[ref.relayUrl].orEmpty().find { it.id == ref.groupId }
                        ?: byRelay.values.flatten().find { it.id == ref.groupId }
                    JoinedGroup(
                        relayUrl = ref.relayUrl,
                        meta =
                        meta ?: GroupMetadata(
                            id = ref.groupId,
                            name = null,
                            about = null,
                            picture = null,
                            isPublic = true,
                            isOpen = true,
                        ),
                    )
                }
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
    }
}
