package org.nostr.nostrord.ui.screens.profile

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.Screen

/**
 * Edit profile screen - allows users to update their profile information.
 *
 * Features:
 * - Edit display name
 * - Edit username
 * - Edit about/bio
 * - Edit avatar URL
 * - Edit NIP-05 identifier
 * - Save changes to Nostr network
 */
@Composable
fun EditProfileScreen(
    onNavigate: (Screen) -> Unit,
    forceDesktop: Boolean = false
) {
    val vm = viewModel { EditProfileViewModel(AppModule.nostrRepository) }

    val userMetadata by vm.userMetadata.collectAsState()
    val publicKey = vm.getPublicKey()
    val currentUserMetadata = publicKey?.let { userMetadata[it] }

    // Form state - initialize with current values
    // Single "Name" field populates both display_name and name in the event
    var name by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.displayName ?: currentUserMetadata?.name ?: "")
    }
    var about by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.about ?: "")
    }
    var pictureUrl by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.picture ?: "")
    }
    var bannerUrl by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.banner ?: "")
    }
    var nip05 by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.nip05 ?: "")
    }
    var lightningAddress by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.lud16 ?: "")
    }
    var website by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.website ?: "")
    }

    // UI state
    var isSaving by remember { mutableStateOf(false) }
    var showSuccessMessage by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Auto-hide success message and navigate back
    LaunchedEffect(showSuccessMessage) {
        if (showSuccessMessage) {
            kotlinx.coroutines.delay(1000)
            onNavigate(Screen.Profile)
        }
    }

    // Auto-hide error message
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            kotlinx.coroutines.delay(3000)
            errorMessage = null
        }
    }

    val onSave: () -> Unit = {
        isSaving = true
        errorMessage = null
        val nameValue = name.ifBlank { null }
        vm.saveProfile(
            displayName = nameValue,
            name = nameValue,
            about = about.ifBlank { null },
            picture = pictureUrl.ifBlank { null },
            banner = bannerUrl.ifBlank { null },
            nip05 = nip05.ifBlank { null },
            lud16 = lightningAddress.ifBlank { null },
            website = website.ifBlank { null }
        ) { result ->
            isSaving = false
            if (result.isSuccess) {
                showSuccessMessage = true
            } else {
                errorMessage = result.exceptionOrNull()?.message ?: "Failed to update profile"
            }
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = !forceDesktop

        if (isCompact) {
            EditProfileScreenMobile(
                name = name,
                about = about,
                pictureUrl = pictureUrl,
                bannerUrl = bannerUrl,
                nip05 = nip05,
                lightningAddress = lightningAddress,
                website = website,
                pubkey = publicKey,
                isSaving = isSaving,
                showSuccessMessage = showSuccessMessage,
                errorMessage = errorMessage,
                onNameChange = { name = it },
                onAboutChange = { about = it },
                onPictureUrlChange = { pictureUrl = it },
                onBannerUrlChange = { bannerUrl = it },
                onNip05Change = { nip05 = it },
                onLightningAddressChange = { lightningAddress = it },
                onWebsiteChange = { website = it },
                onSave = onSave
            )
        } else {
            EditProfileScreenDesktop(
                name = name,
                about = about,
                pictureUrl = pictureUrl,
                bannerUrl = bannerUrl,
                nip05 = nip05,
                lightningAddress = lightningAddress,
                website = website,
                pubkey = publicKey,
                isSaving = isSaving,
                showSuccessMessage = showSuccessMessage,
                errorMessage = errorMessage,
                onNameChange = { name = it },
                onAboutChange = { about = it },
                onPictureUrlChange = { pictureUrl = it },
                onBannerUrlChange = { bannerUrl = it },
                onNip05Change = { nip05 = it },
                onLightningAddressChange = { lightningAddress = it },
                onWebsiteChange = { website = it },
                onSave = onSave
            )
        }
    }
}
