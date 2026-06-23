package org.nostr.nostrord.ui.screens.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.ui.Identifier
import org.nostr.nostrord.ui.components.IdentifierRow
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * The keys themselves on the Backup screen, shared by the mobile and desktop layouts so the two
 * (and the web BackupPanel) stay identical. Public key cycles npub / nprofile / hex with a QR; the
 * private key is reveal-gated (LOCAL accounts only) and offers nsec / hex plus a NIP-49 ncryptsec
 * export. Bunker / NIP-07 accounts hold no local key and see an explainer instead.
 */
@Composable
fun BackupKeysSections(
    vm: BackupViewModel,
    modifier: Modifier = Modifier,
) {
    val revealed by vm.revealed.collectAsState()
    val passphrase by vm.passphrase.collectAsState()
    val ncryptsec by vm.ncryptsec.collectAsState()
    val encrypting by vm.encrypting.collectAsState()
    val error by vm.error.collectAsState()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        BackupCard {
            FieldLabel("Public key")
            Spacer(Modifier.height(Spacing.sm))
            IdentifierRow(ids = vm.publicIds, showQr = true)
        }

        if (vm.canExportPrivate) {
            BackupCard {
                FieldLabel("Private key", color = NostrordColors.Error)
                Spacer(Modifier.height(Spacing.sm))
                if (!revealed) {
                    Text(
                        "nsec1••••••••••••••••••••••••••••••••••••",
                        color = NostrordColors.LightRed,
                        fontFamily = FontFamily.Monospace,
                        style = NostrordTypography.Caption,
                    )
                    Spacer(Modifier.height(Spacing.sm))
                    OutlinedButton(onClick = { vm.reveal() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Reveal private key")
                    }
                } else {
                    IdentifierRow(ids = vm.privateDirectIds())

                    Spacer(Modifier.height(Spacing.md))
                    FieldLabel("Encrypted backup (ncryptsec)")
                    Spacer(Modifier.height(Spacing.sm))
                    if (ncryptsec == null) {
                        OutlinedTextField(
                            value = passphrase,
                            onValueChange = { vm.setPassphrase(it) },
                            label = { Text("Choose a password") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            isError = error != null,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        error?.let {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(it, color = NostrordColors.Error, style = NostrordTypography.Caption)
                        }
                        Spacer(Modifier.height(Spacing.sm))
                        Button(
                            onClick = { vm.encrypt() },
                            enabled = !encrypting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (encrypting) "Encrypting…" else "Encrypt")
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "Keep this password safe. Without it the encrypted backup cannot be recovered.",
                            color = NostrordColors.TextSecondary,
                            style = NostrordTypography.Caption,
                        )
                    } else {
                        IdentifierRow(ids = listOf(Identifier("ncryptsec", ncryptsec!!)))
                        Spacer(Modifier.height(Spacing.xs))
                        TextButton(onClick = { vm.setPassphrase("") }) {
                            Text("Use a different password")
                        }
                    }

                    Spacer(Modifier.height(Spacing.sm))
                    TextButton(
                        onClick = { vm.hide() },
                        colors = ButtonDefaults.textButtonColors(contentColor = NostrordColors.TextSecondary),
                    ) {
                        Text("Hide private key")
                    }
                }
            }
        } else {
            BackupCard {
                FieldLabel("Private key")
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text =
                    when (vm.authMethod) {
                        AuthMethod.BUNKER -> "Your private key stays in your bunker (NIP-46) and is never exposed here."
                        AuthMethod.NIP07 -> "Your private key stays in your browser extension (NIP-07) and is never exposed here."
                        else -> "No private key is available for this account."
                    },
                    color = NostrordColors.TextSecondary,
                    style = NostrordTypography.Caption,
                )
            }
        }
    }
}

@Composable
private fun BackupCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = NostrordColors.Surface),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun FieldLabel(
    text: String,
    color: androidx.compose.ui.graphics.Color = NostrordColors.TextSecondary,
) {
    Text(text, color = color, style = NostrordTypography.SectionHeader)
}
