package org.nostr.nostrord.ui.screens.backup

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule

@Composable
fun BackupScreen(forceDesktop: Boolean = false) {
    // Keyed by the active account so switching accounts rebuilds the VM with the new keys
    // (BackupViewModel reads the keys once at construction).
    val activeId by AppModule.accountStore.activeId.collectAsState()
    val vm = viewModel(key = activeId) { BackupViewModel() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (forceDesktop) {
            BackupScreenDesktop(vm)
        } else {
            BackupScreenMobile(vm)
        }
    }
}
