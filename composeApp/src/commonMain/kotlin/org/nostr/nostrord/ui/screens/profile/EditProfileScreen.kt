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
    onNavigate: (Screen) -> Unit
) {
    val vm = viewModel { EditProfileViewModel(AppModule.nostrRepository) }

    val userMetadata by vm.userMetadata.collectAsState()
    val publicKey = vm.getPublicKey()
    val currentUserMetadata = publicKey?.let { userMetadata[it] }

    // Form state - initialize with current values
    var displayName by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.displayName ?: "")
    }
    var username by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.name ?: "")
    }
    var about by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.about ?: "")
    }
    var pictureUrl by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.picture ?: "")
    }
    var nip05 by remember(currentUserMetadata) {
        mutableStateOf(currentUserMetadata?.nip05 ?: "")
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
        vm.saveProfile(
            displayName = displayName.ifBlank { null },
            name = username.ifBlank { null },
            about = about.ifBlank { null },
            picture = pictureUrl.ifBlank { null },
            nip05 = nip05.ifBlank { null }
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
        val isCompact = maxWidth < 600.dp

        if (isCompact) {
            EditProfileScreenMobile(
                displayName = displayName,
                username = username,
                about = about,
                pictureUrl = pictureUrl,
                nip05 = nip05,
                pubkey = publicKey,
                isSaving = isSaving,
                showSuccessMessage = showSuccessMessage,
                errorMessage = errorMessage,
                onDisplayNameChange = { displayName = it },
                onUsernameChange = { username = it },
                onAboutChange = { about = it },
                onPictureUrlChange = { pictureUrl = it },
                onNip05Change = { nip05 = it },
                onSave = onSave
            )
        } else {
            EditProfileScreenDesktop(
                displayName = displayName,
                username = username,
                about = about,
                pictureUrl = pictureUrl,
                nip05 = nip05,
                pubkey = publicKey,
                isSaving = isSaving,
                showSuccessMessage = showSuccessMessage,
                errorMessage = errorMessage,
                onDisplayNameChange = { displayName = it },
                onUsernameChange = { username = it },
                onAboutChange = { about = it },
                onPictureUrlChange = { pictureUrl = it },
                onNip05Change = { nip05 = it },
                onSave = onSave
            )
        }
    }
}
