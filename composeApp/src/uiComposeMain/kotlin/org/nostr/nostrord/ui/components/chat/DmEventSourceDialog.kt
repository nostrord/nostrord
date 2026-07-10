package org.nostr.nostrord.ui.components.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.nostr.nostrord.ui.theme.NostrordColors
import org.nostr.nostrord.ui.theme.NostrordShapes
import org.nostr.nostrord.ui.theme.Spacing

/**
 * "View source" for a DM: the decrypted kind:14 rumor as pretty JSON plus the relays
 * its gift wrap was seen on this session. Mirrors the web DmPage source modal.
 */
@Composable
fun DmEventSourceDialog(
    json: String,
    relays: List<String>,
    onCopyJson: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = NostrordColors.Surface,
        title = { Text("Message source", color = NostrordColors.TextPrimary) },
        text = {
            Column {
                Box(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .clip(NostrordShapes.shapeSmall)
                        .background(NostrordColors.BackgroundFloating)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState())
                        .padding(Spacing.sm),
                ) {
                    // softWrap = false so long lines scroll horizontally instead of wrapping
                    // (web parity: the .dm-source-json box scrolls).
                    Text(
                        json,
                        color = NostrordColors.TextContent,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                    )
                }
                if (relays.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    Text(
                        "Seen on",
                        color = NostrordColors.TextPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    relays.forEach { relay ->
                        Text(
                            relay,
                            color = NostrordColors.TextMuted,
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        },
        confirmButton = {
            // Primary filled action, matching the web modal's purple "Copy JSON" button.
            Button(
                onClick = {
                    onCopyJson()
                    onDismiss()
                },
                colors =
                ButtonDefaults.buttonColors(
                    containerColor = NostrordColors.Primary,
                    contentColor = Color.White,
                ),
            ) { Text("Copy JSON") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = NostrordColors.TextSecondary)
            }
        },
    )
}
