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
 * npub, and the joined groups where they appear. "Groups in common" is limited to
 * groups whose member list was already fetched; an empty list means none known, not
 * proven absence.
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
            repo.joinedGroupsByRelay,
            repo.groupsByRelay,
            repo.groupMembers,
            repo.groupAdmins,
        ) { joined, byRelay, members, admins ->
            joined
                .flatMap { (relay, ids) ->
                    val metas = byRelay[relay].orEmpty().associateBy { it.id }
                    ids
                        .filter { id -> pubkey in members[id].orEmpty() || pubkey in admins[id].orEmpty() }
                        .map { id ->
                            ProfileGroup(
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
                                isAdmin = pubkey in admins[id].orEmpty(),
                                memberCount = members[id].orEmpty().size,
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

    init {
        // The kind:0 may not be cached (deep link straight to a profile), and the
        // public group list (kind:10009) is only fetched on demand. The own contact
        // list (kind:3) drives the Follow button state.
        viewModelScope.launch { repo.requestUserMetadata(setOf(pubkey)) }
        viewModelScope.launch { repo.requestUserGroupList(pubkey) }
        viewModelScope.launch { repo.requestContactList() }
    }
}
