package org.nostr.nostrord.ui.screens.profile

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.nostr.Nip19
import org.nostr.nostrord.ui.Screen
import org.nostr.nostrord.utils.rememberClipboardWriter

/**
 * Profile screen - user settings and account management.
 *
 * Features:
 * - User avatar and name display
 * - Public key display with copy
 * - Navigation to backup keys
 * - Navigation to relay settings
 * - Logout option
 *
 * No bottom navigation bar - uses back button for navigation.
 */
@Composable
fun ProfileScreen(
    onNavigate: (Screen) -> Unit,
    onLogout: () -> Unit = {}
) {
    val vm = viewModel { ProfileViewModel(AppModule.nostrRepository) }

    val userMetadata by vm.userMetadata.collectAsState()
    val publicKey = vm.getPublicKey()
    val npub = remember(publicKey) { publicKey?.let { Nip19.encodeNpub(it) } }
    val currentUserMetadata = publicKey?.let { userMetadata[it] }

    val copyToClipboard = rememberClipboardWriter()
    var showCopiedMessage by remember { mutableStateOf(false) }

    LaunchedEffect(showCopiedMessage) {
        if (showCopiedMessage) {
            kotlinx.coroutines.delay(2000)
            showCopiedMessage = false
        }
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isCompact = maxWidth < 600.dp

        if (isCompact) {
            ProfileScreenMobile(
                displayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
                username = currentUserMetadata?.name,
                avatarUrl = currentUserMetadata?.picture,
                pubkey = publicKey,
                npub = npub,
                about = currentUserMetadata?.about,
                showCopiedMessage = showCopiedMessage,
                onCopyNpub = {
                    npub?.let {
                        copyToClipboard(it)
                        showCopiedMessage = true
                    }
                },
                onEditProfile = { onNavigate(Screen.EditProfile) },
                onBackupKeys = { onNavigate(Screen.BackupPrivateKey) },
                onRelaySettings = { onNavigate(Screen.RelaySettings) },
                onLogout = { vm.logout(onLogout) }
            )
        } else {
            ProfileScreenDesktop(
                displayName = currentUserMetadata?.displayName ?: currentUserMetadata?.name,
                username = currentUserMetadata?.name,
                avatarUrl = currentUserMetadata?.picture,
                pubkey = publicKey,
                npub = npub,
                about = currentUserMetadata?.about,
                showCopiedMessage = showCopiedMessage,
                onCopyNpub = {
                    npub?.let {
                        copyToClipboard(it)
                        showCopiedMessage = true
                    }
                },
                onEditProfile = { onNavigate(Screen.EditProfile) },
                onBackupKeys = { onNavigate(Screen.BackupPrivateKey) },
                onRelaySettings = { onNavigate(Screen.RelaySettings) },
                onLogout = { vm.logout(onLogout) }
            )
        }
    }
}
