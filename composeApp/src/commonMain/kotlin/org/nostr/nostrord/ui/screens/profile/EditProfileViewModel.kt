package org.nostr.nostrord.ui.screens.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepositoryApi
import org.nostr.nostrord.utils.toKotlinResult

class EditProfileViewModel(private val repo: NostrRepositoryApi) : ViewModel() {

    val userMetadata = repo.userMetadata

    fun getPublicKey() = repo.getPublicKey()

    fun saveProfile(
        displayName: String?,
        name: String?,
        about: String?,
        picture: String?,
        banner: String?,
        nip05: String?,
        lud16: String?,
        website: String?,
        onResult: (kotlin.Result<Unit>) -> Unit
    ) {
        viewModelScope.launch {
            onResult(repo.updateProfileMetadata(displayName, name, about, picture, banner, nip05, lud16, website).toKotlinResult())
        }
    }
}
