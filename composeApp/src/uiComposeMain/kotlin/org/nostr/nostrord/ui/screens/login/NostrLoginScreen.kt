package org.nostr.nostrord.ui.screens.login

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import nostrord.composeapp.generated.resources.Res
import nostrord.composeapp.generated.resources.nostrord_logo
import org.jetbrains.compose.resources.painterResource
import org.nostr.nostrord.nostr.Nip07
import org.nostr.nostrord.ui.components.forms.AppSegmentedTabs
import org.nostr.nostrord.ui.components.forms.SegmentedTab
import org.nostr.nostrord.ui.screens.login.components.BunkerLoginTab
import org.nostr.nostrord.ui.screens.login.components.ExtensionLoginTab
import org.nostr.nostrord.ui.screens.login.components.PrivateKeyLoginTab
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes

sealed class LoginTab(
    val title: String,
    val icon: ImageVector,
) {
    object PrivateKey : LoginTab("Private Key", Icons.Default.Key)

    object Bunker : LoginTab("Bunker", Icons.Default.Shield)

    object Extension : LoginTab("Extension", Icons.Default.Extension)
}

@Composable
fun NostrLoginScreen(
    modifier: Modifier = Modifier,
    onLoginSuccess: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf<LoginTab>(LoginTab.PrivateKey) }
    val tabs =
        remember {
            buildList {
                add(LoginTab.PrivateKey)
                add(LoginTab.Bunker)
                if (Nip07.isAvailable()) add(LoginTab.Extension)
            }
        }

    Box(
        modifier =
        modifier
            .fillMaxSize()
            .background(
                // Theme-aware page gradient: corners around the main background
                // (mirrors the web's .login-page / prototype page-gradient).
                Brush.linearGradient(
                    colors =
                    listOf(
                        NostrordColors.PageGradientFrom,
                        NostrordColors.Background,
                        NostrordColors.PageGradientTo,
                    ),
                ),
            ),
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            // Centers the card when it fits; the column still scrolls when taller.
            verticalArrangement = Arrangement.Center,
        ) {
            // Main login card — wider when 3 tabs are shown to prevent label cramming
            val cardMaxWidth = if (tabs.size >= 3) 500.dp else 448.dp
            Card(
                modifier =
                Modifier
                    .widthIn(max = cardMaxWidth)
                    .fillMaxWidth(),
                shape = NostrordShapes.shapeXLarge,
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            ) {
                Column(
                    modifier = Modifier.padding(32.dp),
                ) {
                    // Card header: logo on the brand tile, app name, tagline
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Image(
                            painter = painterResource(Res.drawable.nostrord_logo),
                            contentDescription = "Nostrord",
                            modifier =
                            Modifier
                                .size(56.dp)
                                .clip(NostrordShapes.shapeXLarge)
                                .background(NostrordColors.Primary),
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Nostrord",
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = NostrordColors.TextPrimary,
                        )
                        Text(
                            text = "decentralized groups on nostr",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NostrordColors.TextMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    // Tab selector (shared segmented-tabs component)
                    AppSegmentedTabs(
                        tabs = tabs.map { SegmentedTab(it.title, it.icon) },
                        selectedIndex = tabs.indexOf(selectedTab),
                        onSelect = { selectedTab = tabs[it] },
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Tab content
                    when (selectedTab) {
                        LoginTab.PrivateKey -> PrivateKeyLoginTab(onLoginSuccess)
                        LoginTab.Bunker -> BunkerLoginTab(onLoginSuccess)
                        LoginTab.Extension -> ExtensionLoginTab(onLoginSuccess)
                    }
                }
            }
        }
    }
}
