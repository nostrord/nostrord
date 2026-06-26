package org.nostr.nostrord.ui.screens.relay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi

class RelayViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    val currentRelayUrl = repo.currentRelayUrl

    fun switchRelay(url: String) {
        // switchRelay offloads its own blocking SecureStorage reads onto Dispatchers.Default
        // internally, so this stays on the (test-controlled) viewModel scope.
        viewModelScope.launch { repo.switchRelay(url) }
    }
}
