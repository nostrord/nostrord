package org.nostr.nostrord.ui.screens.backup

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun BackupScreen(forceDesktop: Boolean = false) {
    val vm = viewModel { BackupViewModel() }
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (forceDesktop) {
            BackupScreenDesktop(vm)
        } else {
            BackupScreenMobile(vm)
        }
    }
}
