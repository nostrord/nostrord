package org.nostr.nostrord.ui.screens.login.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.network.NostrRepository
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

@Composable
fun BunkerLoginTab(onLoginSuccess: () -> Unit) {
    var bunkerUrl by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isConnecting by remember { mutableStateOf(false) }
    var connectionStatus by remember { mutableStateOf<String?>(null) }
    val authUrl by NostrRepository.authUrl.collectAsState()
    val scope = rememberCoroutineScope()

    val uriHandler = LocalUriHandler.current

    LaunchedEffect(authUrl) {
        authUrl?.let { url ->
            connectionStatus = "Opening browser for approval..."
            try {
                uriHandler.openUri(url)
            } catch (e: Exception) {
                errorMessage = "Could not open browser. Please open this URL manually."
            }
        }
    }

    Column {
        // Description
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.shapeSmall,
            color = NostrordColors.Primary.copy(alpha = 0.1f)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Security,
                    contentDescription = null,
                    tint = NostrordColors.Primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    "Connect to a remote signer for secure key management",
                    color = NostrordColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Input field
        OutlinedTextField(
            value = bunkerUrl,
            onValueChange = { bunkerUrl = it; errorMessage = null },
            placeholder = {
                Text(
                    "bunker://<pubkey>?relay=wss://...",
                    color = NostrordColors.TextMuted
                )
            },
            singleLine = false,
            maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
            textStyle = LocalTextStyle.current.copy(color = Color.White),
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    tint = NostrordColors.TextMuted
                )
            },
            shape = NostrordShapes.inputShape,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = NostrordColors.Primary,
                unfocusedBorderColor = NostrordColors.SurfaceVariant,
                cursorColor = NostrordColors.Primary,
                focusedContainerColor = NostrordColors.InputBackground,
                unfocusedContainerColor = NostrordColors.InputBackground
            ),
            enabled = !isConnecting
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            "Get your bunker URL from nsec.app, Amber, or other NIP-46 signers",
            color = NostrordColors.TextMuted,
            style = MaterialTheme.typography.labelSmall
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Connect button
        Button(
            onClick = {
                scope.launch {
                    isConnecting = true
                    errorMessage = null
                    connectionStatus = "Connecting..."
                    NostrRepository.clearAuthUrl()

                    try {
                        NostrRepository.loginWithBunker(bunkerUrl)
                        connectionStatus = "Connected!"
                        onLoginSuccess()
                    } catch (e: Exception) {
                        errorMessage = "Connection failed: ${e.message}"
                        connectionStatus = null
                    } finally {
                        isConnecting = false
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = bunkerUrl.isNotBlank() && !isConnecting,
            shape = NostrordShapes.buttonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = NostrordColors.Primary,
                contentColor = Color.White,
                disabledContainerColor = NostrordColors.Primary.copy(alpha = 0.5f),
                disabledContentColor = Color.White.copy(alpha = 0.7f)
            )
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = Color.White,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connecting...", fontWeight = FontWeight.SemiBold)
            } else {
                Icon(
                    imageVector = Icons.Default.Link,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Connect to Bunker", fontWeight = FontWeight.SemiBold)
            }
        }

        // Show auth URL if waiting for approval
        authUrl?.let { url ->
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = NostrordShapes.shapeMedium,
                color = NostrordColors.WarningOrange.copy(alpha = 0.1f)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Verified,
                            contentDescription = null,
                            tint = NostrordColors.WarningOrange,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Approval Required",
                            color = NostrordColors.WarningOrange,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "A browser window should have opened. Please approve the connection there, then wait...",
                        color = NostrordColors.TextContent,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        url,
                        color = NostrordColors.TextLink,
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        connectionStatus?.let {
            Spacer(modifier = Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (it == "Connected!") {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = NostrordColors.Success,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    it,
                    color = if (it == "Connected!") NostrordColors.Success else NostrordColors.Primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
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

        Spacer(modifier = Modifier.height(20.dp))

        // Benefits card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = NostrordShapes.shapeMedium,
            color = NostrordColors.SurfaceVariant.copy(alpha = 0.5f)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        tint = NostrordColors.Success,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Why use a Bunker?",
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                BenefitItem("Your private key never leaves the signer")
                BenefitItem("Approve each signing request")
                BenefitItem("Works with hardware signers")
                BenefitItem("Revoke access anytime")
            }
        }
    }
}

@Composable
private fun BenefitItem(text: String) {
    Row(
        modifier = Modifier.padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            tint = NostrordColors.Success.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text,
            color = NostrordColors.TextSecondary,
            style = MaterialTheme.typography.bodySmall
        )
    }
}
