package org.nostr.nostrord.ui.screens.backup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.auth.AuthMethod
import org.nostr.nostrord.ui.Identifier
import org.nostr.nostrord.ui.components.IdentifierRow
import org.nostr.nostrord.ui.components.forms.appFieldTextStyle
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordTypography
import org.nostr.nostrord.ui.theme.Spacing

/**
 * The keys themselves on the Backup screen, shared by the mobile and desktop layouts and matched
 * to the web BackupPanel. Public key cycles npub / nprofile / hex with a QR; the private key is
 * reveal-gated (LOCAL accounts only) and offers nsec / hex plus a NIP-49 ncryptsec export. Bunker /
 * NIP-07 accounts hold no local key and see an explainer instead.
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

                    // Encrypted-backup subsection, divider-set like the web .backup-subsection.
                    Spacer(Modifier.height(Spacing.md))
                    HorizontalDivider(color = NostrordColors.Divider)
                    Spacer(Modifier.height(Spacing.md))
                    FieldLabel("Encrypted backup (ncryptsec)")
                    Spacer(Modifier.height(Spacing.sm))
                    if (ncryptsec == null) {
                        val canEncrypt = !encrypting && passphrase.length >= MIN_BACKUP_PASSWORD
                        Row(
                            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            OutlinedTextField(
                                value = passphrase,
                                onValueChange = { vm.setPassphrase(it) },
                                placeholder = { Text("Choose a password", fontSize = 14.sp) },
                                visualTransformation = PasswordVisualTransformation(),
                                singleLine = true,
                                isError = error != null,
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                                keyboardActions = KeyboardActions(onDone = { if (canEncrypt) vm.encrypt() }),
                                textStyle = appFieldTextStyle(),
                                modifier = Modifier.weight(1f),
                            )
                            Button(
                                onClick = { vm.encrypt() },
                                enabled = canEncrypt,
                                modifier = Modifier.fillMaxHeight(),
                            ) {
                                Text(if (encrypting) "Encrypting…" else "Encrypt")
                            }
                        }
                        error?.let {
                            Spacer(Modifier.height(Spacing.xs))
                            Text(it, color = NostrordColors.Error, style = NostrordTypography.Caption)
                        }
                        Spacer(Modifier.height(Spacing.xs))
                        Text(
                            "Keep this password safe. Without it the encrypted backup cannot be recovered.",
                            color = NostrordColors.TextSecondary,
                            style = NostrordTypography.Caption,
                        )
                    } else {
                        IdentifierRow(ids = listOf(Identifier("ncryptsec", ncryptsec!!)))
                        TextButton(onClick = { vm.setPassphrase("") }) {
                            Text("Use a different password")
                        }
                    }

                    // Footer: divider + right-aligned quiet hide, like the web .backup-footer.
                    Spacer(Modifier.height(Spacing.md))
                    HorizontalDivider(color = NostrordColors.Divider)
                    Spacer(Modifier.height(Spacing.xs))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(
                            onClick = { vm.hide() },
                            colors = ButtonDefaults.textButtonColors(contentColor = NostrordColors.TextSecondary),
                        ) {
                            Text("Hide private key")
                        }
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
