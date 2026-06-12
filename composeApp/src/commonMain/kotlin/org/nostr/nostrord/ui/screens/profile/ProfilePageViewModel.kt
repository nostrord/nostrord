package org.nostr.nostrord.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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

    /** Drives the ADMIN badge next to the name (admin in any shared group). */
    val isAdminSomewhere: StateFlow<Boolean> =
        groupsWithUser
            .map { list -> list.any { it.isAdmin } }
            .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        // The kind:0 may not be cached (deep link straight to a profile).
        viewModelScope.launch { repo.requestUserMetadata(setOf(pubkey)) }
    }
}
