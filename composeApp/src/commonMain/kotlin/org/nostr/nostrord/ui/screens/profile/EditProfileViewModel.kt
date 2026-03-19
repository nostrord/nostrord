package org.nostr.nostrord.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi

class EditProfileViewModel(private val repo: NostrRepositoryApi) : ViewModel() {

    val userMetadata = repo.userMetadata

    fun getPublicKey() = repo.getPublicKey()

    fun saveProfile(
        displayName: String?,
        name: String?,
        about: String?,
        picture: String?,
        nip05: String?,
        onResult: (kotlin.Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            val result = repo.updateProfileMetadata(displayName, name, about, picture, nip05)
            onResult(result)
        }
    }
}
