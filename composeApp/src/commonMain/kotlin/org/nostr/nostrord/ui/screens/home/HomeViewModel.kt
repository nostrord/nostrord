package org.nostr.nostrord.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository

class HomeViewModel(private val repo: NostrRepository) : ViewModel() {

    val groups = repo.groups
    val connectionState = repo.connectionState
    val currentRelayUrl = repo.currentRelayUrl
    val joinedGroups = repo.joinedGroups
    val userMetadata = repo.userMetadata
    val unreadCounts = repo.unreadCounts

    fun getPublicKey() = repo.getPublicKey()

    fun connect() {
        viewModelScope.launch { repo.connect() }
    }
}
