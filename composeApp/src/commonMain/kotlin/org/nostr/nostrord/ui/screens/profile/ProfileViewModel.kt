package org.nostr.nostrord.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi

class ProfileViewModel(private val repo: NostrRepositoryApi) : ViewModel() {

    val userMetadata = repo.userMetadata

    fun getPublicKey() = repo.getPublicKey()

    fun logout(onComplete: () -> Unit) {
        viewModelScope.launch {
            repo.logout()
            onComplete()
        }
    }
}
