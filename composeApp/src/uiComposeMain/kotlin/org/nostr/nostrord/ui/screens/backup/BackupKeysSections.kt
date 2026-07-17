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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        } else if (vm.pomegranateCentral != null) {
            PomegranateBackupSection(vm)
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

/**
 * Backup section for pomegranate (Login with Google) accounts: sharded-key explainer,
 * per-operator nsec export, and disconnect from the central server. Runtime-reachable
 * on the web only (the marker is written by the web Google login), but rendered from
 * the same ViewModel state so the two UIs stay in lockstep.
 */
@Composable
private fun PomegranateBackupSection(vm: BackupViewModel) {
    val export by vm.pomExport.collectAsState()
    val disconnect by vm.pomDisconnect.collectAsState()
    val pomError by vm.pomError.collectAsState()
    var disconnectArmed by remember { mutableStateOf(false) }

    BackupCard {
        FieldLabel("Private key")
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text =
            "This account signs in with Google: the key was created for you, split into shards held by " +
                "independent operators, and never stored whole anywhere. Signing happens remotely (NIP-46) " +
                "through the central server. You can reassemble and export it below.",
            color = NostrordColors.TextSecondary,
            style = NostrordTypography.Caption,
        )
        pomError?.let {
            Spacer(Modifier.height(Spacing.sm))
            Text(it, color = NostrordColors.Error, style = NostrordTypography.Caption)
        }
        Spacer(Modifier.height(Spacing.md))
        when (val state = export) {
            BackupViewModel.PomegranateExport.Idle -> {
                OutlinedButton(onClick = { vm.startPomegranateExport() }) {
                    Text("Export private key")
                }
            }

            BackupViewModel.PomegranateExport.Authing -> {
                OutlinedButton(onClick = {}, enabled = false) {
                    Text("Waiting for Google sign-in…")
                }
            }

            is BackupViewModel.PomegranateExport.Recovering -> {
                Text(
                    "Recovered ${state.recovered} of ${state.threshold} shards. Any ${state.threshold} operators are enough; a failing one can be skipped.",
                    color = NostrordColors.TextSecondary,
                    style = NostrordTypography.Caption,
                )
                Spacer(Modifier.height(Spacing.sm))
                state.operators.forEach { op ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(op.host, color = NostrordColors.TextSecondary, style = NostrordTypography.Caption)
                        when (op.status) {
                            BackupViewModel.ShardStatus.Recovered ->
                                Text("Recovered", color = NostrordColors.Success, style = NostrordTypography.Caption)

                            else -> {
                                TextButton(
                                    onClick = { vm.recoverPomegranateShard(op.operator.url) },
                                    enabled = state.operators.none { it.status == BackupViewModel.ShardStatus.Recovering },
                                ) {
                                    Text(
                                        when (op.status) {
                                            BackupViewModel.ShardStatus.Recovering -> "Waiting…"
                                            BackupViewModel.ShardStatus.Failed -> "Retry"
                                            else -> "Recover"
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = { vm.cancelPomegranateExport() }) { Text("Cancel") }
            }

            is BackupViewModel.PomegranateExport.Done -> {
                FieldLabel("Private key (nsec)")
                Spacer(Modifier.height(Spacing.sm))
                IdentifierRow(ids = listOf(Identifier("nsec", state.nsec)))
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "Store it somewhere safe. With the nsec you can log in on any device via the Private Key option, with or without Google.",
                    color = NostrordColors.TextSecondary,
                    style = NostrordTypography.Caption,
                )
                TextButton(onClick = { vm.cancelPomegranateExport() }) { Text("Hide") }
            }
        }
    }

    Spacer(Modifier.height(Spacing.md))
    BackupCard {
        FieldLabel("Disconnect from central server")
        Spacer(Modifier.height(Spacing.sm))
        Text(
            "Removes this account from the central server: Google login and remote signing stop working. " +
                "Export your nsec first; without it the account becomes inaccessible.",
            color = NostrordColors.TextSecondary,
            style = NostrordTypography.Caption,
        )
        Spacer(Modifier.height(Spacing.md))
        if (disconnect == BackupViewModel.PomegranateDisconnect.Done) {
            Text(
                "Disconnected. This account now works only with its exported key.",
                color = NostrordColors.TextSecondary,
                style = NostrordTypography.Caption,
            )
        } else {
            Button(
                onClick = { if (!disconnectArmed) disconnectArmed = true else vm.disconnectPomegranate() },
                enabled = disconnect != BackupViewModel.PomegranateDisconnect.Working,
                colors = ButtonDefaults.buttonColors(containerColor = NostrordColors.Error),
            ) {
                Text(
                    when {
                        disconnect == BackupViewModel.PomegranateDisconnect.Working -> "Disconnecting…"
                        disconnectArmed -> "Click again to confirm"
                        else -> "Disconnect from central server"
                    },
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
