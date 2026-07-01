package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.components.forms.AppTextField
import org.nostr.nostrord.ui.components.forms.FormError
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Startup unlock for a NIP-49 password-protected account: only the ncryptsec is on
 * disk, so session restore stops until the user enters the password (or dismisses
 * and uses the regular login screen). Shown by the App root whenever
 * `pendingUnlockAccount` is set; a successful unlock logs in and clears it.
 */
@Composable
fun UnlockAccountDialog(account: Account) {
    val vm = viewModel { LoginViewModel(AppModule.nostrRepository) }
    var password by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    fun unlock() {
        if (password.isEmpty() || isLoading) return
        isLoading = true
        errorMessage = null
        vm.unlockWithPassword(password) { result ->
            isLoading = false
            if (result.isFailure) {
                errorMessage = result.exceptionOrNull()?.message ?: "Wrong password"
            }
            // Success clears pendingUnlock upstream, which removes this dialog.
        }
    }

    AlertDialog(
        onDismissRequest = { if (!isLoading) vm.dismissUnlock() },
        containerColor = NostrordColors.Surface,
        titleContentColor = NostrordColors.TextPrimary,
        textContentColor = NostrordColors.TextSecondary,
        icon = {
            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                tint = NostrordColors.Primary,
            )
        },
        title = { Text("Unlock ${account.label}") },
        text = {
            Column {
                Text(
                    "This account's key is encrypted on this device (NIP-49). " +
                        "Enter the password to unlock it.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(modifier = Modifier.height(12.dp))
                AppTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    placeholder = "Password",
                    leadingIcon = Icons.Default.Lock,
                    masked = true,
                    keyboardType = KeyboardType.Password,
                    enabled = !isLoading,
                    onDone = { unlock() },
                )
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(12.dp))
                    FormError(it)
                }
            }
        },
        confirmButton = {
            AppButton(
                text = if (isLoading) "Unlocking..." else "Unlock",
                onClick = { unlock() },
                enabled = password.isNotEmpty() && !isLoading,
                loading = isLoading,
            )
        },
        dismissButton = {
            TextButton(onClick = { vm.dismissUnlock() }, enabled = !isLoading) {
                Text("Not now", color = NostrordColors.TextSecondary)
            }
        },
    )
}
