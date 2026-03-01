package org.nostr.nostrord.ui.screens.login

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nostrord.composeapp.generated.resources.Res
import nostrord.composeapp.generated.resources.nostrord_logo
import org.jetbrains.compose.resources.painterResource
import org.nostr.nostrord.ui.screens.login.components.BunkerLoginTab
import org.nostr.nostrord.ui.screens.login.components.PrivateKeyLoginTab
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

sealed class LoginTab(val title: String, val icon: ImageVector) {
    object PrivateKey : LoginTab("Private Key", Icons.Default.Key)
    object Bunker : LoginTab("Bunker", Icons.Default.Shield)
}

@Composable
fun NostrLoginScreen(onLoginSuccess: () -> Unit) {
    var selectedTab by remember { mutableStateOf<LoginTab>(LoginTab.PrivateKey) }
    val tabs = listOf(LoginTab.PrivateKey, LoginTab.Bunker)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        NostrordColors.BackgroundDark,
                        NostrordColors.Background,
                        NostrordColors.Background
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(40.dp))

            // Logo/Brand section
            Image(
                painter = painterResource(Res.drawable.nostrord_logo),
                contentDescription = "Nostrord",
                modifier = Modifier.size(100.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Nostrord",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )

            Text(
                text = "Connect to the Nostr network",
                style = MaterialTheme.typography.bodyMedium,
                color = NostrordColors.TextMuted,
                modifier = Modifier.padding(top = 4.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Main login card
            Card(
                modifier = Modifier
                    .widthIn(max = 400.dp)
                    .fillMaxWidth(),
                shape = NostrordShapes.shapeLarge,
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    // Tab selector
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = NostrordShapes.shapeMedium,
                        color = NostrordColors.SurfaceVariant
                    ) {
                        Row(
                            modifier = Modifier.padding(4.dp)
                        ) {
                            tabs.forEach { tab ->
                                val isSelected = selectedTab == tab
                                val backgroundColor by animateColorAsState(
                                    if (isSelected) NostrordColors.Primary else Color.Transparent
                                )

                                Surface(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clip(NostrordShapes.shapeSmall)
                                        .clickable { selectedTab = tab },
                                    shape = NostrordShapes.shapeSmall,
                                    color = backgroundColor
                                ) {
                                    Row(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 12.dp),
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = tab.icon,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp),
                                            tint = if (isSelected) Color.White else NostrordColors.TextMuted
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = tab.title,
                                            style = MaterialTheme.typography.labelLarge,
                                            color = if (isSelected) Color.White else NostrordColors.TextMuted,
                                            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Tab content
                    when (selectedTab) {
                        LoginTab.PrivateKey -> PrivateKeyLoginTab(onLoginSuccess)
                        LoginTab.Bunker -> BunkerLoginTab(onLoginSuccess)
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Footer hint
            Text(
                text = "New to Nostr? Generate a key to get started instantly.",
                style = MaterialTheme.typography.bodySmall,
                color = NostrordColors.TextMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 300.dp)
            )

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}
