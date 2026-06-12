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
import org.nostr.nostrord.network.GroupMetadata
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.notifications.NotificationHistoryStore

/** A joined group together with the relay that hosts it (needed for navigation). */
data class JoinedGroup(
    val relayUrl: String,
    val meta: GroupMetadata,
)

/**
 * Screen logic for the new-design Home page (prototype Home), shared by the Compose
 * and React UIs: the user's joined groups across every relay (kind:10009), filtered
 * by the search query. The friends / communities / people tabs are layout-only with
 * placeholder content for now, so they carry no logic here yet.
 */
class HomePageViewModel(
    repo: NostrRepositoryApi,
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
}
