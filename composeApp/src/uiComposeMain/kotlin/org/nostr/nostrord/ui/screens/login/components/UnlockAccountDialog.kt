package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.auth.Account
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

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
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessage = null
                    },
                    placeholder = { Text("Password", color = NostrordColors.TextMuted) },
                    singleLine = true,
                    enabled = !isLoading,
                    modifier = Modifier.fillMaxWidth(),
                    textStyle = LocalTextStyle.current.copy(color = NostrordColors.TextContent),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { unlock() }),
                    shape = NostrordShapes.inputShape,
                    colors =
                    OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NostrordColors.Primary,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = NostrordColors.Primary,
                        focusedContainerColor = NostrordColors.BackgroundFloating,
                        unfocusedContainerColor = NostrordColors.BackgroundFloating,
                    ),
                )
                errorMessage?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(it, color = NostrordColors.Error, style = MaterialTheme.typography.bodySmall)
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
