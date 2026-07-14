package org.nostr.nostrord.ui.components

import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.nostr.nostrord.ui.theme.NostrordColors

/**
 * The single confirm dialog for the native UI (Compose counterpart of the web `confirmDialog`
 * builder): a title and a body line on the standard Material AlertDialog (compact width, content
 * left-aligned, buttons bottom-right), with a Cancel / confirm pair. [destructive] turns the confirm
 * button red for irreversible actions (delete, remove, leave, log out); otherwise it is the brand
 * color. Every simple yes/no confirm routes through this so the dialogs read identically and the
 * AlertDialog block is not re-hand-rolled per call site.
 *
 * Both buttons are filled (the brand/red confirm and a neutral cancel) so the confirm dialogs read
 * as deliberate actions rather than two plain text links. A null [cancelLabel] drops the cancel
 * button entirely, so the same component also serves single-button info / error dialogs (the
 * confirm button then reads "OK").
 *
 * [confirmEnabled] gates the confirm action (e.g. while an async op is in flight). Dialogs that host
 * their own input field or bespoke content (rename, add-relay, the delete-group warning) keep their
 * own AlertDialog.
 *
 * [onCancel], when set, makes the cancel button an ACTION of its own instead of a close
 * (backdrop/Esc still run [onDismiss]) — for prompts whose two buttons are both decisions,
 * like the group-invite Accept/Decline.
 */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmLabel: String = "Confirm",
    cancelLabel: String? = "Cancel",
    destructive: Boolean = false,
    confirmEnabled: Boolean = true,
    cancelEnabled: Boolean = true,
    onCancel: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Fixed width so every confirm dialog is the same size regardless of how long its
        // message is (Material would otherwise content-size each one differently). 480dp keeps a
        // typical one-line message on a single line, matching the natural width that read best.
        modifier = Modifier.width(480.dp),
        containerColor = NostrordColors.Surface,
        titleContentColor = NostrordColors.TextPrimary,
        textContentColor = NostrordColors.TextSecondary,
        shape = RoundedCornerShape(16.dp),
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = if (destructive) NostrordColors.Error else NostrordColors.Primary,
                    contentColor = Color.White,
                ),
                shape = RoundedCornerShape(8.dp),
            ) { Text(confirmLabel, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton =
        cancelLabel?.let { label ->
            {
                Button(
                    onClick = onCancel ?: onDismiss,
                    enabled = cancelEnabled,
                    colors =
                    ButtonDefaults.buttonColors(
                        containerColor = NostrordColors.SurfaceVariant,
                        contentColor = NostrordColors.TextPrimary,
                    ),
                    shape = RoundedCornerShape(8.dp),
                ) { Text(label, fontWeight = FontWeight.SemiBold) }
            }
        },
    )
}
