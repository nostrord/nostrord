package org.nostr.nostrord.ui.screens.profile

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
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
    val scope = rememberCoroutineScope()
    val userMetadata by NostrRepository.userMetadata.collectAsState()
    val publicKey = NostrRepository.getPublicKey()
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

    // Auto-hide success message
    LaunchedEffect(showSuccessMessage) {
        if (showSuccessMessage) {
            kotlinx.coroutines.delay(2000)
            showSuccessMessage = false
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
        scope.launch {
            isSaving = true
            errorMessage = null
            try {
                val result = NostrRepository.updateProfileMetadata(
                    displayName = displayName.ifBlank { null },
                    name = username.ifBlank { null },
                    about = about.ifBlank { null },
                    picture = pictureUrl.ifBlank { null },
                    nip05 = nip05.ifBlank { null }
                )
                if (result.isSuccess) {
                    showSuccessMessage = true
                    // Navigate back after short delay
                    kotlinx.coroutines.delay(1000)
                    onNavigate(Screen.Profile)
                } else {
                    errorMessage = result.exceptionOrNull()?.message ?: "Failed to update profile"
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "Failed to update profile"
            } finally {
                isSaving = false
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
