package org.nostr.nostrord.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi

/**
 * Shared logic for the Settings "Muted users" panel (NIP-51 kind:10000). Both the web and
 * Compose panels consume this VM so listing and unmuting live in one place.
 */
class MutedUsersViewModel(
    private val repo: NostrRepositoryApi,
) : ViewModel() {
    /** Muted pubkeys, stable-sorted so the list doesn't jump as relay echoes arrive. */
    val muted: StateFlow<List<String>> =
        repo.mutedPubkeys
            .map { it.sorted() }
            .stateIn(viewModelScope, SharingStarted.Eagerly, repo.mutedPubkeys.value.sorted())

    val userMetadata = repo.userMetadata

    init {
        // Resolve names/avatars for the listed pubkeys; the metadata layer dedups
        // cached entries, so re-collecting on every change is cheap.
        viewModelScope.launch {
            repo.mutedPubkeys.collect { pubkeys ->
                if (pubkeys.isNotEmpty()) repo.requestUserMetadata(pubkeys)
            }
        }
    }

    fun unmute(pubkey: String) {
        viewModelScope.launch { repo.unmuteUser(pubkey) }
    }
}
