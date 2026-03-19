package org.nostr.nostrord

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.nostr.nostrord.network.NostrRepositoryApi

class AppViewModel(private val repo: NostrRepositoryApi) : ViewModel() {

    val isInitialized = repo.isInitialized
    val isLoggedIn = repo.isLoggedIn
    val isBunkerVerifying = repo.isBunkerVerifying

    init {
        viewModelScope.launch {
            withTimeoutOrNull(30_000) {
                repo.initialize()
            } ?: repo.forceInitialized()
        }
    }
}
