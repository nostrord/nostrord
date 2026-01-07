package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.nostr.KeyPair
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import kotlin.random.Random

@Composable
fun PrivateKeyLoginTab(onLoginSuccess: () -> Unit) {
    var privateKey by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showGeneratedKey by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var showKey by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun generatePrivateKey(): String {
        val bytes = Random.Default.nextBytes(32)
        return bytes.joinToString("") { byte ->
            byte.toUByte().toString(16).padStart(2, '0')
        }
    }

    fun login() {
        scope.launch {
            isLoading = true
            errorMessage = null
            try {
                val keyPair = KeyPair.fromPrivateKeyHex(privateKey)
                val pubKey = keyPair.publicKeyHex
                NostrRepository.loginSuspend(privateKey, pubKey)
                onLoginSuccess()
            } catch (e: Exception) {
                errorMessage = "Invalid private key or login failed"
            } finally {
                isLoading = false
            }
        }
    }

    Column {
        // Input field with icon
        OutlinedTextField(
            value = privateKey,
            onValueChange = { privateKey = it; errorMessage = null },
            placeholder = {
                Text(
                    "Enter your private key (hex or nsec)",
                    color = NostrordColors.TextMuted
                )
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(color = Color.White),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.VpnKey,
                    contentDescription = null,
                    tint = NostrordColors.TextMuted
                )
            },
            trailingIcon = {
                IconButton(onClick = { showKey = !showKey }) {
                    Icon(
                        imageVector = if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (showKey) "Hide key" else "Show key",
                        tint = NostrordColors.TextMuted
                    )
                }
            },
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(onDone = { if (privateKey.isNotBlank()) login() }),
            shape = NostrordShapes.inputShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NostrordColors.Primary,
                unfocusedBorderColor = NostrordColors.SurfaceVariant,
                cursorColor = NostrordColors.Primary,
                focusedContainerColor = NostrordColors.InputBackground,
                unfocusedContainerColor = NostrordColors.InputBackground
            ),
            enabled = !isLoading
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Login button
        Button(
            onClick = { login() },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = privateKey.isNotBlank() && !isLoading,
            shape = NostrordShapes.buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = NostrordColors.Primary,
                contentColor = Color.White,
                disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.7f)
            )
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Login,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Login", fontWeight = FontWeight.SemiBold)
            }
        }

        // Divider
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = NostrordColors.Divider
            )
            Text(
                text = "or",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = NostrordColors.TextMuted,
                style = MaterialTheme.typography.bodySmall
            )
            HorizontalDivider(
                modifier = Modifier.weight(1f),
                color = NostrordColors.Divider
            )
        }

        // Generate new key button
        OutlinedButton(
            onClick = {
                scope.launch {
                    isLoading = true
                    errorMessage = null
                    try {
                        val newPrivateKey = generatePrivateKey()
                        privateKey = newPrivateKey
                        showGeneratedKey = true

                        val keyPair = KeyPair.fromPrivateKeyHex(newPrivateKey)
                        val pubKey = keyPair.publicKeyHex
                        NostrRepository.loginSuspend(newPrivateKey, pubKey)
                        onLoginSuccess()
                    } catch (e: Exception) {
                        errorMessage = "Failed to generate key"
                    } finally {
                        isLoading = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = !isLoading,
            shape = NostrordShapes.buttonShape,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = NostrordColors.Success
            ),
            border = ButtonDefaults.outlinedButtonBorder(enabled = true).copy(
                brush = androidx.compose.ui.graphics.SolidColor(NostrordColors.Success.copy(alpha = 0.5f))
            )
        ) {
            Icon(
                imageVector = Icons.Default.AutoAwesome,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Generate New Identity", fontWeight = FontWeight.SemiBold)
        }

        if (showGeneratedKey && privateKey.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            GeneratedKeyCard(privateKey)
        }

        errorMessage?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = NostrordShapes.shapeSmall,
                color = NostrordColors.Error.copy(alpha = 0.1f)
            ) {
                Text(
                    text = it,
                    color = NostrordColors.Error,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
