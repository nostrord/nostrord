package org.nostr.nostrord.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi

class HomeViewModel(private val repo: NostrRepositoryApi) : ViewModel() {

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

    fun getPublicKey() = repo.getPublicKey()

    fun connect() {
        viewModelScope.launch { repo.connect() }
    }

    fun removeRelay(url: String) {
        viewModelScope.launch { repo.removeRelay(url) }
    }
}
