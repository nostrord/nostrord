package org.nostr.nostrord.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.UnlockState
import org.nostr.nostrord.ui.components.buttons.AppButton
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography

private enum class PromptMode { Unlock, Setup, LegacyMigration }

@Composable
fun PassphraseGate(content: @Composable () -> Unit) {
    val state by SecureStorage.unlockState.collectAsState()
    when (state) {
        UnlockState.Initializing -> Box(Modifier.fillMaxSize().background(NostrordColors.Background))
        UnlockState.NeedsPassphrase -> PassphrasePrompt(PromptMode.Unlock)
        UnlockState.NeedsLegacyMigration -> PassphrasePrompt(PromptMode.LegacyMigration)
        UnlockState.Unlocked, UnlockState.NeedsPassphraseSetup -> {
            Box(Modifier.fillMaxSize()) {
                content()
                if (state == UnlockState.NeedsPassphraseSetup) {
                    PassphrasePrompt(PromptMode.Setup)
                }
            }
        }
    }
}

@Composable
private fun PassphrasePrompt(mode: PromptMode) {
    var passphrase by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val isNew = mode != PromptMode.Unlock
    val canSubmit =
        if (isNew) {
            passphrase.length >= 8 && passphrase == confirm
        } else {
            passphrase.isNotEmpty()
        }

    fun submit() {
        if (!canSubmit) return
        val ok =
            if (isNew) {
                SecureStorage.setupPassphrase(passphrase)
            } else {
                SecureStorage.unlockWithPassphrase(passphrase)
            }
        if (!ok) {
            error = if (isNew) "Could not set the passphrase." else "Incorrect passphrase."
            passphrase = ""
            confirm = ""
        }
    }

    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(NostrordColors.Background),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
            Modifier
                .widthIn(max = 440.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(NostrordColors.Surface)
                .padding(32.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text =
                when (mode) {
                    PromptMode.Unlock -> "Enter your passphrase"
                    PromptMode.Setup -> "Protect your saved credentials"
                    PromptMode.LegacyMigration -> "Set a passphrase to keep your data"
                },
                style = NostrordTypography.ServerHeader,
                color = NostrordColors.TextPrimary,
            )
            Text(
                text =
                when (mode) {
                    PromptMode.Unlock ->
                        "Your operating system keychain is not available. Enter the passphrase you set previously to unlock your stored data."
                    PromptMode.Setup ->
                        "Your operating system keychain is not available. Set a passphrase to encrypt the credentials you just saved on this device. The passphrase cannot be recovered."
                    PromptMode.LegacyMigration ->
                        "Your operating system keychain is not available and we found data from a previous version. Set a passphrase to migrate and protect it. The passphrase cannot be recovered."
                },
                style = NostrordTypography.MessageBody,
                color = NostrordColors.TextSecondary,
            )
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = passphrase,
                onValueChange = {
                    passphrase = it
                    error = null
                },
                label = { Text("Passphrase", color = NostrordColors.TextMuted) },
                singleLine = true,
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = if (isNew) ImeAction.Next else ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                trailingIcon = {
                    IconButton(onClick = { visible = !visible }) {
                        Icon(
                            imageVector = if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (visible) "Hide passphrase" else "Show passphrase",
                            tint = NostrordColors.TextSecondary,
                        )
                    }
                },
                colors = passphraseFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )

            if (isNew) {
                OutlinedTextField(
                    value = confirm,
                    onValueChange = {
                        confirm = it
                        error = null
                    },
                    label = { Text("Confirm passphrase", color = NostrordColors.TextMuted) },
                    singleLine = true,
                    visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    colors = passphraseFieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = "At least 8 characters. Must match.",
                    style = NostrordTypography.Caption,
                    color = NostrordColors.TextSecondary,
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = NostrordTypography.MessageBody,
                    color = NostrordColors.Error,
                )
            }

            Spacer(Modifier.height(4.dp))
            AppButton(
                text = if (isNew) "Set passphrase" else "Unlock",
                onClick = ::submit,
                enabled = canSubmit,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun passphraseFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = NostrordColors.Primary,
    unfocusedBorderColor = NostrordColors.Divider,
    focusedContainerColor = NostrordColors.InputBackground,
    unfocusedContainerColor = NostrordColors.InputBackground,
    cursorColor = NostrordColors.Primary,
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
)
