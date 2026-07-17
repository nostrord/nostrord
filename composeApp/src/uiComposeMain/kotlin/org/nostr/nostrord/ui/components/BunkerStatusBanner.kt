package org.nostr.nostrord.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.nostr.nostrord.auth.ActiveAccountManager
import org.nostr.nostrord.auth.NostrSigner
import org.nostr.nostrord.di.AppModule
import org.nostr.nostrord.network.BunkerState
import org.nostr.nostrord.network.bunkerBannerCopy
import org.nostr.nostrord.storage.SecureStorage
import org.nostr.nostrord.storage.loadPomegranateDisconnectedFor
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography

/**
 * Floating warning shown when the active account signs through a NIP-46 bunker
 * the app currently can't reach. The app can't tell an offline signer from a
 * connection that was deleted on the signer side, so it never logs the user out
 * (see [org.nostr.nostrord.network.AuthManager.markSignerUnreachable]). This
 * banner explains the situation and lets the user choose: "Reconnect" re-reads
 * the saved bunker URL from storage via [ensureBunkerConnected] (no re-paste),
 * or "Log out" to sign in differently. (issue #85)
 */
@Composable
fun BunkerStatusBanner(modifier: Modifier = Modifier) {
    val repo = AppModule.nostrRepository
    val loggedIn by repo.isLoggedIn.collectAsState()
    val state by repo.bunkerState.collectAsState()
    // Cold-boot restore reuses the verifying flag + full-screen loader
    // ("Reconnecting to signer..."), so suppress the banner then to avoid double UI.
    val verifying by repo.isBunkerVerifying.collectAsState()
    // Follow the ACTIVE session's signer, not an inline auth flag: after "Log out"
    // removes the bunker account and switches to another, an active non-bunker
    // signer hides the banner.
    val session by ActiveAccountManager.session.collectAsState()
    val activeIsBunker = session?.signer is NostrSigner.Bunker

    // Show for an active bunker account that is unreachable or actively
    // reconnecting. The spinner is bound to the Reconnecting state, which
    // AuthManager always resolves (mutex + short deadlines), so it can never get
    // stuck on "Reconnecting…". (issue #85)
    val reconnecting = state is BunkerState.Reconnecting
    val show =
        loggedIn &&
            activeIsBunker &&
            !verifying &&
            (state is BunkerState.Unreachable || reconnecting)

    val reason = (state as? BunkerState.Unreachable)?.reason
    val pomegranateGone = session?.pubkey?.let { SecureStorage.loadPomegranateDisconnectedFor(it) } == true
    val copy = bunkerBannerCopy(reconnecting, reason, pomegranateGone)

    val scope = rememberCoroutineScope()

    AnimatedVisibility(
        visible = show,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier,
    ) {
        Card(
            modifier =
            Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .widthIn(max = 520.dp),
            shape = RoundedCornerShape(12.dp),
            colors =
            CardDefaults.cardColors(
                containerColor = NostrordColors.SurfaceVariant,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = NostrordColors.Warning,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(
                            text = copy.title,
                            style = NostrordTypography.Caption,
                            color = NostrordColors.Warning,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = copy.body,
                            style = NostrordTypography.Caption,
                            color = NostrordColors.TextContent,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Collapse the buttons' 48dp minimum touch target so the action
                // row hugs its content. Otherwise the reserved height adds slack
                // below the text, making the card's bottom padding look larger
                // than the top.
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = {
                                scope.launch {
                                    // Mirror the explicit-logout flow used by Settings:
                                    // remove the active account if registered, else a
                                    // plain logout. Routes the UI back to login.
                                    val activeId = AppModule.accountStore.activeId.value
                                    if (activeId != null) {
                                        AppModule.accountManager.removeAccount(activeId)
                                    } else {
                                        repo.logout()
                                    }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "Log out",
                                style = NostrordTypography.Caption,
                                color = NostrordColors.TextContent,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (copy.canReconnect) {
                            Spacer(Modifier.width(4.dp))
                            TextButton(
                                enabled = !reconnecting,
                                onClick = {
                                    scope.launch { repo.ensureBunkerConnected() }
                                },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                if (reconnecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        color = NostrordColors.Warning,
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Refresh,
                                        contentDescription = null,
                                        tint = NostrordColors.Warning,
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                                Spacer(Modifier.width(4.dp))
                                Text(
                                    text = if (reconnecting) "Reconnecting…" else "Reconnect",
                                    style = NostrordTypography.Caption,
                                    color = NostrordColors.Warning,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
