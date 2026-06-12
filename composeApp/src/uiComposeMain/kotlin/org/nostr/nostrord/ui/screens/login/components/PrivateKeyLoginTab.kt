package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.screens.login.LoginViewModel
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import kotlin.random.Random

/**
 * Private key login: nsec / hex / NIP-49 ncryptsec input with the prototype's field
 * layout (uppercase label above, hint below, floating input surface). Pasting an
 * ncryptsec reveals the key-password field; key parsing and decryption live in
 * [LoginViewModel] so the web tab behaves identically.
 */
@Composable
fun PrivateKeyLoginTab(onLoginSuccess: () -> Unit) {
    val vm = viewModel { LoginViewModel(AppModule.nostrRepository) }

    var privateKey by remember { mutableStateOf("") }
    var keyPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    // Hex of the most recently generated key. Drives both the GeneratedKeyCard
    // visibility (so the warning + key value stay on screen until the user
    // explicitly clicks Login) and the isNewIdentity flag forwarded to the
    // repository — matches the web flow where the user has time to copy the
    // key before signing in.
    var generatedKey by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }

    val isEncrypted = vm.isEncryptedKeyInput(privateKey)
    val canLogin = privateKey.isNotBlank() && (!isEncrypted || keyPassword.isNotEmpty()) && !isLoading

    fun generatePrivateKey(): String {
        val bytes = Random.Default.nextBytes(32)
        return bytes.joinToString("") { byte ->
            byte.toUByte().toString(16).padStart(2, '0')
        }
    }

    fun login() {
        if (!canLogin) return
        isLoading = true
        errorMessage = null
        val input = privateKey.trim()
        // Only flag isNewIdentity when the input is exactly the just-generated
        // key — typing/pasting an existing nsec is never a "new" identity.
        val isNewIdentity = generatedKey != null && input == generatedKey
        vm.loginWithPrivateKeyInput(
            input,
            password = keyPassword.takeIf { isEncrypted },
            isNewIdentity = isNewIdentity,
        ) { result ->
            isLoading = false
            if (result.isSuccess) {
                onLoginSuccess()
            } else {
                errorMessage = result.exceptionOrNull()?.message ?: "Invalid private key or login failed"
            }
        }
    }

    Column {
        FieldLabel("Private key (nsec, hex or ncryptsec)")
        LoginField(
            value = privateKey,
            onValueChange = {
                privateKey = it
                errorMessage = null
            },
            placeholder = "nsec1... · ncryptsec1...",
            leadingIcon = Icons.Default.VpnKey,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            onDone = { login() },
            enabled = !isLoading,
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showKey) "Hide key" else "Show key",
                        tint = NostrordColors.TextMuted,
                    )
                }
            },
        )
        FieldHint("Your key never leaves this device.")

        if (isEncrypted) {
            Spacer(modifier = Modifier.height(16.dp))
            FieldLabel("Key password")
            LoginField(
                value = keyPassword,
                onValueChange = {
                    keyPassword = it
                    errorMessage = null
                },
                placeholder = "Password",
                leadingIcon = Icons.Default.Lock,
                visualTransformation = PasswordVisualTransformation(),
                onDone = { login() },
                enabled = !isLoading,
            )
            FieldHint("This key is encrypted (NIP-49); enter its password to unlock it.")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Login button
        Button(
            onClick = { login() },
            modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = canLogin,
            shape = NostrordShapes.buttonShape,
            colors =
            ButtonDefaults.buttonColors(
                containerColor = NostrordColors.Primary,
                contentColor = Color.White,
                disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.7f),
            ),
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp,
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Login,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Login", fontWeight = FontWeight.SemiBold)
            }
        }

        // Divider
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = NostrordColors.Divider,
            )
            Text(
                text = "or",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = NostrordColors.TextMuted,
                style = MaterialTheme.typography.bodySmall,
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = NostrordColors.Divider,
            )
        }

        // Generate new key button (prototype secondary: filled grey) — only
        // generates + populates + shows the warning card. Login is deferred to
        // the Login button so the user has time to copy/save the key.
        Button(
            onClick = {
                errorMessage = null
                val newPrivateKey = generatePrivateKey()
                privateKey = newPrivateKey
                generatedKey = newPrivateKey
            },
            modifier =
            Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = !isLoading,
            shape = NostrordShapes.buttonShape,
            colors =
            ButtonDefaults.buttonColors(
                containerColor = NostrordColors.SurfaceVariant,
                contentColor = NostrordColors.TextContent,
                disabledContainerColor = NostrordColors.SurfaceVariant.copy(alpha = 0.5f),
                disabledContentColor = NostrordColors.TextMuted,
            ),
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Generate New Key", fontWeight = FontWeight.SemiBold)
        }

        // GeneratedKeyCard sticks around once the user pressed Generate (no
        // auto-login any more). They can clear it by editing the input — if
        // it diverges from `generatedKey`, the Login button just signs in as
        // an existing identity (isNewIdentity flips to false).
        generatedKey?.takeIf { it.isNotEmpty() }?.let { key ->
            Spacer(modifier = Modifier.height(16.dp))
            GeneratedKeyCard(key)
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = NostrordShapes.shapeSmall,
                color = NostrordColors.Error.copy(alpha = 0.1f),
            ) {
                Text(
                    text = it,
                    color = NostrordColors.Error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Prototype field label: small uppercase bold, above the input. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = NostrordColors.TextSecondary,
        modifier = Modifier.padding(bottom = 6.dp),
    )
}

/** Prototype field hint: muted, below the input. */
@Composable
private fun FieldHint(text: String) {
    Text(
        text = text,
        fontSize = 12.sp,
        color = NostrordColors.TextMuted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** Prototype input: floating surface, transparent border, brand border on focus. */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    leadingIcon: ImageVector,
    visualTransformation: VisualTransformation,
    onDone: () -> Unit,
    enabled: Boolean,
    trailingIcon: (@Composable () -> Unit)? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        placeholder = { Text(placeholder, color = NostrordColors.TextMuted) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        textStyle = LocalTextStyle.current.copy(color = NostrordColors.TextContent),
        leadingIcon = {
            Icon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = NostrordColors.TextMuted,
            )
        },
        trailingIcon = trailingIcon,
        visualTransformation = visualTransformation,
        keyboardOptions =
        KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        shape = NostrordShapes.inputShape,
        colors =
        OutlinedTextFieldDefaults.colors(
            focusedBorderColor = NostrordColors.Primary,
            unfocusedBorderColor = Color.Transparent,
            cursorColor = NostrordColors.Primary,
            focusedContainerColor = NostrordColors.BackgroundFloating,
            unfocusedContainerColor = NostrordColors.BackgroundFloating,
        ),
        enabled = enabled,
    )
}
