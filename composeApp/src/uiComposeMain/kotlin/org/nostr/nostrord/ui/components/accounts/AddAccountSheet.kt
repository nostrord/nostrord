package org.nostr.nostrord.ui.components.accounts

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.ui.screens.login.LoginMethods
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * Modal that adds a new signed-in account. Centered dialog matching the web
 * AddAccountModal: a header (title + "You'll keep <name> signed in.") over the
 * shared [LoginMethods] tab picker (Private Key / Bunker / Extension), the same
 * interface as the login screen. A successful login swaps the active account via
 * the warm-swap path in [org.nostr.nostrord.network.NostrRepository.finishLoginInit];
 * the previous account stays registered.
 */
@Composable
fun AddAccountSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onAdded: (displayLabel: String) -> Unit,
) {
    if (!visible) return

    val accounts by AppModule.accountStore.accounts.collectAsState()
    val activeId by AppModule.accountStore.activeId.collectAsState()
    val userMetadata by AppModule.nostrRepository.userMetadata.collectAsState()
    val activeAccount = accounts.firstOrNull { it.id == activeId }
    val activeDisplayName =
        activeAccount?.let { acc ->
            userMetadata[acc.pubkey]?.displayName?.takeIf { it.isNotBlank() }
                ?: userMetadata[acc.pubkey]?.name?.takeIf { it.isNotBlank() }
                ?: acc.label
        }

    val notifyAdded: () -> Unit = {
        // After the warm-swap finishes, the active pubkey is the new one. Look its
        // label up from the freshly-emitted accountStore so the snackbar prefers the
        // user's profile name when available.
        val newActive = AppModule.accountStore.active
        val newMeta = newActive?.pubkey?.let { userMetadata[it] }
        val label =
            newMeta?.displayName?.takeIf { it.isNotBlank() }
                ?: newMeta?.name?.takeIf { it.isNotBlank() }
                ?: newActive?.label
                ?: "account"
        onAdded(label)
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties =
        DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
            usePlatformDefaultWidth = false,
        ),
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.7f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                )
                .safeDrawingPadding(),
            contentAlignment = Alignment.Center,
        ) {
            Card(
                modifier =
                Modifier
                    .widthIn(max = 480.dp)
                    .fillMaxWidth(0.9f)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
            ) {
                Column(
                    modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                ) {
                    // Header: title + subtitle, close button on the right.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Add account",
                                color = NostrordColors.TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                            )
                            if (activeDisplayName != null) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "You'll keep $activeDisplayName signed in.",
                                    color = NostrordColors.TextMuted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Close",
                                tint = NostrordColors.TextSecondary,
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    LoginMethods(onLoginSuccess = notifyAdded)
                }
            }
        }
    }
}
