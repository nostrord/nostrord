package org.nostr.nostrord.ui.screens.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi

class RelayViewModel(private val repo: NostrRepositoryApi) : ViewModel() {

    val currentRelayUrl = repo.currentRelayUrl

    fun switchRelay(url: String) {
        viewModelScope.launch { repo.switchRelay(url) }
    }
}
