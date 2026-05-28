package org.nostr.nostrord.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi

class HomeViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    val groups = repo.groups
    val groupsByRelay = repo.groupsByRelay
    val loadingRelays = repo.loadingRelays
    val connectionState = repo.connectionState
    val currentRelayUrl = repo.currentRelayUrl
    val joinedGroups = repo.joinedGroups
    val joinedGroupsByRelay = repo.joinedGroupsByRelay
    val userMetadata = repo.userMetadata
    val unreadCounts = repo.unreadCounts
    val relayMetadata = repo.relayMetadata
    val pendingDeepLinkRelay = repo.pendingDeepLinkRelay
    val kind10009Relays = repo.kind10009Relays
    val restrictedRelays = repo.restrictedRelays
    val fullGroupListFetchedRelays = repo.fullGroupListFetchedRelays

    fun getPublicKey() = repo.getPublicKey()

    fun isGroupFetchLazy(relayUrl: String) = repo.isGroupFetchLazy(relayUrl)

    /** Lazily fetch the relay's full group list (kind:39000). No-op if already in flight. */
    fun requestFullGroupList(relayUrl: String) {
        viewModelScope.launch { repo.requestFullGroupListForRelay(relayUrl) }
    }

    fun connect() {
        viewModelScope.launch { repo.connect() }
    }

    fun removeRelay(url: String) {
        viewModelScope.launch { repo.removeRelay(url) }
    }

    fun addRelay(url: String) {
        viewModelScope.launch {
            repo.addRelay(url)
            // Connect to the relay immediately — addRelay only saves the list;
            // autoConnectFirstRelay is skipped when any primary client already exists.
            repo.switchRelay(url)
        }
    }

    fun forgetGroup(
        groupId: String,
        relayUrl: String,
    ) {
        viewModelScope.launch { repo.forgetGroup(groupId, relayUrl) }
    }
}
