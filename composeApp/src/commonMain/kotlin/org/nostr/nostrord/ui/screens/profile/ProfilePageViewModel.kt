package org.nostr.nostrord.ui.screens.profile

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
import org.nostr.nostrord.network.UserMetadata
import org.nostr.nostrord.nostr.Nip19

/** A joined group where the profiled user appears, with their role there. */
data class ProfileGroup(
    val relayUrl: String,
    val meta: GroupMetadata,
    val isAdmin: Boolean,
    val memberCount: Int,
)

/**
 * Screen logic for the new-design user profile page (prototype Profile, /u/:pubkey),
 * shared by the Compose and React UIs: the user's kind:0 metadata (fetched on open),
 * npub, and the groups where they appear. Group membership is limited to groups whose
 * member list (kind:39002) was already fetched plus the user's own public kind:10009;
 * an empty list means none known, not proven absence (NIP-29 offers no reverse lookup).
 */
class ProfilePageViewModel(
    private val repo: NostrRepositoryApi,
    val pubkey: String,
) : ViewModel() {
    val npub: String = runCatching { Nip19.encodeNpub(pubkey) }.getOrDefault(pubkey)

    val isSelf: Boolean get() = repo.getPublicKey() == pubkey

    val metadata: StateFlow<UserMetadata?> =
        repo.userMetadata
            .map { it[pubkey] }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val groupsWithUser: StateFlow<List<ProfileGroup>> =
        combine(
            repo.groupsByRelay,
            repo.groupMembers,
            repo.groupAdmins,
        ) { byRelay, members, admins ->
            // Scan every group we already know (not only the ones the active account
            // joined): NIP-29 has no "groups for pubkey" query, so a user who never
            // published a kind:10009 is only discoverable through the member lists
            // (kind:39002) we have already fetched, e.g. a group browsed but not joined.
            byRelay
                .flatMap { (relay, metas) ->
                    metas
                        .filter { meta -> pubkey in members[meta.id].orEmpty() || pubkey in admins[meta.id].orEmpty() }
                        .map { meta ->
                            ProfileGroup(
                                relayUrl = relay,
                                meta = meta,
                                isAdmin = pubkey in admins[meta.id].orEmpty(),
                                memberCount = members[meta.id].orEmpty().size,
                            )
                        }
                }.distinctBy { it.meta.id }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The user's OWN groups: their public kind:10009 list (fetched on open via the
     * outbox relays), merged with the shared-membership view above so private but
     * shared groups still show. Metadata is enriched when a connected relay already
     * served it; unknown groups render with a placeholder name until opened.
     */
    val userGroups: StateFlow<List<ProfileGroup>> =
        combine(
            repo.userGroupLists.map { it[pubkey].orEmpty() },
            groupsWithUser,
            repo.groupsByRelay,
            repo.groupMembers,
            repo.groupAdmins,
        ) { publicRefs, shared, byRelay, members, admins ->
            val fromList =
                publicRefs.map { ref ->
                    val meta = byRelay[ref.relayUrl].orEmpty().find { it.id == ref.groupId }
                        ?: byRelay.values.flatten().find { it.id == ref.groupId }
                    ProfileGroup(
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
                        isAdmin = pubkey in admins[ref.groupId].orEmpty(),
                        memberCount = members[ref.groupId].orEmpty().size,
                    )
                }
            (fromList + shared).distinctBy { it.meta.id }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Drives the ADMIN badge next to the name (admin in any shared group). */
    val isAdminSomewhere: StateFlow<Boolean> =
        groupsWithUser
            .map { list -> list.any { it.isAdmin } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Whether the active account follows this user (NIP-02 kind:3). */
    val isFollowing: StateFlow<Boolean> =
        repo.following
            .map { pubkey in it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** True while a follow/unfollow publish is in flight, to disable the button. */
    private val _isFollowBusy = MutableStateFlow(false)
    val isFollowBusy: StateFlow<Boolean> = _isFollowBusy.asStateFlow()

    /** Follow or unfollow this user, toggling based on the current [isFollowing]. */
    fun toggleFollow() {
        if (isSelf || _isFollowBusy.value) return
        viewModelScope.launch {
            _isFollowBusy.value = true
            try {
                if (isFollowing.value) repo.unfollowUser(pubkey) else repo.followUser(pubkey)
            } finally {
                _isFollowBusy.value = false
            }
        }
    }

    /** Whether the active account mutes this user (NIP-51 kind:10000). */
    val isMuted: StateFlow<Boolean> =
        repo.mutedPubkeys
            .map { pubkey in it }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /** Mute or unmute this user, toggling based on the current [isMuted]. */
    fun toggleMute() {
        if (isSelf) return
        viewModelScope.launch {
            if (isMuted.value) repo.unmuteUser(pubkey) else repo.muteUser(pubkey)
        }
    }

    init {
        // The kind:0 may not be cached (deep link straight to a profile), and the
        // public group list (kind:10009) is only fetched on demand. The own contact
        // list (kind:3) drives the Follow button state.
        // forceStale: opening a profile is an explicit "show me the latest" — revalidate
        // even a cached-but-stale entry (still served instantly from cache meanwhile).
        viewModelScope.launch { repo.requestUserMetadata(setOf(pubkey), forceStale = true) }
        viewModelScope.launch { repo.requestUserGroupList(pubkey) }
        viewModelScope.launch { repo.requestContactList() }

        // Proactively fetch kind:39000 metadata for the user's listed groups we don't
        // already have cached, batched per relay (one pooled connection + REQ each).
        // Without this a group the active account never browsed renders as a raw id
        // until opened. previewRequested dedups so a relay that has no metadata for a
        // group is not re-queried on every recomposition of the list.
        val previewRequested = mutableSetOf<String>()
        viewModelScope.launch {
            combine(
                repo.userGroupLists.map { it[pubkey].orEmpty() },
                repo.groupsByRelay,
            ) { refs, byRelay ->
                refs.filter { ref ->
                    byRelay.values.none { groups -> groups.any { it.id == ref.groupId } } &&
                        previewRequested.add("${ref.relayUrl}'${ref.groupId}")
                }
            }.collect { missing ->
                if (missing.isEmpty()) return@collect
                val relayToGroups =
                    missing.groupBy { it.relayUrl }
                        .mapValues { (_, refs) -> refs.map { it.groupId }.toSet() }
                repo.fetchGroupPreviews(relayToGroups)
            }
        }
    }
}
